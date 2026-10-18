package com.skyshield;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
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
    private final RestClient client = RestClient.create();
    private volatile Instant fetchedAt = Instant.EPOCH;
    private volatile List<Aircraft> cached = List.of();
    private volatile String feedMessage = "Not checked yet";

    public static class Observation {
        public String deviceId;
        public double latitude, longitude;
        public Double altitude, speed, heading;
        public Instant receivedAt;
    }
    public static class Aircraft {
        public String icao24, callsign, originCountry;
        public Double latitude, longitude, altitudeMeters, speedMetersPerSecond, headingDegrees;
        public Boolean onGround;
        public Instant lastContact;
    }
    public record AirspaceResponse(List<Aircraft> aircraft, Instant fetchedAt, String message, String source) {}

    @PostMapping("/remote-id/observations")
    @ResponseStatus(HttpStatus.CREATED)
    public Observation receive(@RequestBody Observation data) {
        if (data == null || data.deviceId == null || data.deviceId.isBlank()
                || !Double.isFinite(data.latitude) || !Double.isFinite(data.longitude)
                || data.latitude < -90 || data.latitude > 90
                || data.longitude < -180 || data.longitude > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tracking observation");
        }
        data.receivedAt = Instant.now();
        tracks.put(data.deviceId, data);
        return data;
    }
    @GetMapping("/remote-id/tracks")
    public List<Observation> getTracks() {
        return tracks.values().stream().sorted(Comparator.comparing(o -> o.deviceId)).toList();
    }
    @GetMapping("/remote-id/status")
    public String status() {
        return tracks.isEmpty() ? "NO LIVE REMOTE ID DATA RECEIVED" : "REMOTE ID DATA RECEIVED";
    }

    // Live ADS-B aircraft data; this is not a drone-specific Remote ID feed.
    @GetMapping("/airspace/aircraft")
    public synchronized AirspaceResponse getLiveAircraft() {
        if (Instant.now().isBefore(fetchedAt.plusSeconds(15))) {
            return new AirspaceResponse(cached, fetchedAt, feedMessage, "OpenSky Network ADS-B");
        }
        try {
            JsonNode response = client.get()
                .uri("https://opensky-network.org/api/states/all?lamin=12.7&lomin=77.2&lamax=13.3&lomax=78.0")
                .retrieve().body(JsonNode.class);
            List<Aircraft> found = new ArrayList<>();
            JsonNode states = response == null ? null : response.path("states");
            if (states != null && states.isArray()) {
                for (JsonNode s : states) {
                    if (!s.isArray() || s.size() < 11 || s.get(5).isNull() || s.get(6).isNull()) continue;
                    Aircraft a = new Aircraft();
                    a.icao24 = str(s,0); a.callsign = str(s,1).trim(); a.originCountry = str(s,2);
                    a.longitude = num(s,5); a.latitude = num(s,6); a.altitudeMeters = num(s,7);
                    a.onGround = s.get(8) != null && s.get(8).asBoolean(false);
                    a.speedMetersPerSecond = num(s,9); a.headingDegrees = num(s,10);
                    Double contact = num(s,4);
                    if (contact != null) a.lastContact = Instant.ofEpochSecond(contact.longValue());
                    found.add(a);
                }
            }
            cached = List.copyOf(found);
            feedMessage = found.size() + " aircraft state(s) returned";
        } catch (Exception ex) {
            feedMessage = "External feed unavailable: " + ex.getClass().getSimpleName();
        }
        fetchedAt = Instant.now();
        return new AirspaceResponse(cached, fetchedAt, feedMessage, "OpenSky Network ADS-B");
    }
    private String str(JsonNode a, int i) {
        JsonNode n = a.get(i); return n == null || n.isNull() ? "" : n.asText("");
    }
    private Double num(JsonNode a, int i) {
        JsonNode n = a.get(i); return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }
}