package com.skyshield;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class RemoteIdController {

    private final ConcurrentHashMap<String, Observation> tracks = new ConcurrentHashMap<>();
    private final ArrayDeque<Alert> receiverAlerts = new ArrayDeque<>();
    private final RestClient client = RestClient.create();

    @org.springframework.beans.factory.annotation.Value("${OPENSKY_CLIENT_ID:}")
    private String openSkyClientId;
    @org.springframework.beans.factory.annotation.Value("${OPENSKY_CLIENT_SECRET:}")
    private String openSkyClientSecret;

    private volatile String accessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;
    private volatile Instant openSkyFetchedAt = Instant.EPOCH;
    private volatile List<Aircraft> cachedAircraft = List.of();
    private volatile String openSkyMessage = "Not checked yet";

    public static class Observation {
        public String deviceId;
        public double latitude;
        public double longitude;
        public Double altitude;
        public Double speed;
        public Double heading;
        public Instant receivedAt;
    }

    public static class Alert {
        public String id;
        public String level;
        public String code;
        public String title;
        public String message;
        public String deviceId;
        public Instant createdAt;
    }

    public static class Aircraft {
        public String icao24;
        public String callsign;
        public String originCountry;
        public Double latitude;
        public Double longitude;
        public Double altitudeMeters;
        public Double speedMetersPerSecond;
        public Double headingDegrees;
        public String squawk;
        public Boolean onGround;
        public Instant lastContact;
    }

    public record OpenSkyResponse(List<Aircraft> aircraft, List<Alert> alerts,
                                  Instant fetchedAt, String message, String source,
                                  boolean authenticated) {}

    @PostMapping("/remote-id/observations")
    @org.springframework.http.HttpStatus.CREATED
    public Observation receive(@RequestBody Observation data) {
        if (data == null || data.deviceId == null || data.deviceId.isBlank()
                || !Double.isFinite(data.latitude) || !Double.isFinite(data.longitude)
                || data.latitude < -90 || data.latitude > 90
                || data.longitude < -180 || data.longitude > 180) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid tracking observation");
        }
        boolean isNew = !tracks.containsKey(data.deviceId);
        data.receivedAt = Instant.now();
        tracks.put(data.deviceId, data);
        Alert alert = new Alert();
        alert.id = data.deviceId + "-" + data.receivedAt.toEpochMilli();
        alert.level = "INFO";
        alert.code = "REMOTE_ID";
        alert.title = isNew ? "New Remote ID track received" : "Remote ID track updated";
        alert.message = "Receiver data received for " + data.deviceId + ". This is not a threat assessment.";
        alert.deviceId = data.deviceId;
        alert.createdAt = data.receivedAt;
        synchronized (receiverAlerts) {
            receiverAlerts.addFirst(alert);
            while (receiverAlerts.size() > 50) receiverAlerts.removeLast();
        }
        return data;
    }

    @GetMapping("/remote-id/tracks")
    public List<Observation> getTracks() {
        return tracks.values().stream().sorted(Comparator.comparing(o -> o.deviceId)).toList();
    }

    @GetMapping("/remote-id/alerts")
    public List<Alert> getReceiverAlerts() {
        synchronized (receiverAlerts) { return new ArrayList<>(receiverAlerts); }
    }

    @GetMapping("/opensky/alerts")
    public OpenSkyResponse getOpenSkyAlerts() {
        if (Instant.now().isBefore(openSkyFetchedAt.plusSeconds(15))) {
            return responseFromCache();
        }
        try {
            String token = getAccessTokenIfConfigured();
            RestClient.RequestHeadersUriSpec<?> request = client.get();
            if (token != null) {
                // The token is applied below to the actual request.
                JsonNode json = client.get().uri("https://opensky-network.org/api/states/all?lamin=12.7&lomin=77.2&lamax=13.3&lomax=78.0")
                        .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class);
                parseStates(json);
            } else {
                JsonNode json = request.uri("https://opensky-network.org/api/states/all?lamin=12.7&lomin=77.2&lamax=13.3&lomax=78.0")
                        .retrieve().body(JsonNode.class);
                parseStates(json);
            }
            openSkyMessage = "OpenSky returned " + cachedAircraft.size() + " aircraft state(s). Emergency alerts are based on squawk codes 7500, 7600 and 7700.";
        } catch (Exception ex) {
            openSkyMessage = "OpenSky feed unavailable: " + ex.getClass().getSimpleName()
                    + ". Check Render logs, API credentials and provider limits.";
        }
        openSkyFetchedAt = Instant.now();
        return responseFromCache();
    }

    private void parseStates(JsonNode json) {
        List<Aircraft> aircraft = new ArrayList<>();
        JsonNode states = json == null ? null : json.path("states");
        if (states != null && states.isArray()) {
            for (JsonNode s : states) {
                if (!s.isArray() || s.size() < 16 || s.get(5).isNull() || s.get(6).isNull()) continue;
                Aircraft a = new Aircraft();
                a.icao24 = str(s, 0);
                a.callsign = str(s, 1).trim();
                a.originCountry = str(s, 2);
                a.longitude = num(s, 5);
                a.latitude = num(s, 6);
                a.altitudeMeters = num(s, 7);
                a.onGround = s.get(8) != null && s.get(8).asBoolean(false);
                a.speedMetersPerSecond = num(s, 9);
                a.headingDegrees = num(s, 10);
                a.squawk = str(s, 14);
                Double contact = num(s, 4);
                if (contact != null) a.lastContact = Instant.ofEpochSecond(contact.longValue());
                aircraft.add(a);
            }
        }
        cachedAircraft = List.copyOf(aircraft);
    }

    private OpenSkyResponse responseFromCache() {
        List<Alert> alerts = new ArrayList<>();
        for (Aircraft a : cachedAircraft) {
            String code = a.squawk;
            if (!"7500".equals(code) && !"7600".equals(code) && !"7700".equals(code)) continue;
            Alert alert = new Alert();
            alert.id = a.icao24 + "-" + code;
            alert.code = code;
            alert.level = "7700".equals(code) ? "EMERGENCY" : "URGENT";
            alert.title = "7700".equals(code) ? "General emergency squawk"
                    : "7600".equals(code) ? "Radio communication failure squawk" : "Hijack squawk";
            alert.message = "OpenSky reports squawk " + code + " for this aircraft. Verify with official aviation authorities; this is not a drone alert.";
            alert.deviceId = a.icao24;
            alert.createdAt = a.lastContact;
            alerts.add(alert);
        }
        alerts.sort(Comparator.comparing(a -> a.code));
        return new OpenSkyResponse(cachedAircraft, alerts, openSkyFetchedAt, openSkyMessage,
                "OpenSky Network live ADS-B state vectors", openSkyClientId != null && !openSkyClientId.isBlank());
    }

    private synchronized String getAccessTokenIfConfigured() {
        if (openSkyClientId == null || openSkyClientId.isBlank()
                || openSkyClientSecret == null || openSkyClientSecret.isBlank()) return null;
        if (accessToken != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(30))) return accessToken;

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", openSkyClientId);
        form.add("client_secret", openSkyClientSecret);
        JsonNode tokenResponse = client.post()
                .uri("https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(JsonNode.class);
        if (tokenResponse == null || tokenResponse.path("access_token").asText("").isBlank()) {
            throw new IllegalStateException("OpenSky OAuth token was not returned");
        }
        accessToken = tokenResponse.path("access_token").asText();
        tokenExpiresAt = Instant.now().plusSeconds(tokenResponse.path("expires_in").asLong(1800));
        return accessToken;
    }

    private String str(JsonNode a, int i) {
        JsonNode n = a.get(i); return n == null || n.isNull() ? "" : n.asText("");
    }
    private Double num(JsonNode a, int i) {
        JsonNode n = a.get(i); return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }
}