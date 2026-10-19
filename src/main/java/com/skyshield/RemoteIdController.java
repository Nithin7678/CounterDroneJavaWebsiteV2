package com.skyshield;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/remote-id")
public class RemoteIdController {

    private final ConcurrentHashMap<String, Observation> tracks = new ConcurrentHashMap<>();
    private final ArrayDeque<Alert> alerts = new ArrayDeque<>();

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
        public String title;
        public String message;
        public String deviceId;
        public Instant createdAt;
    }

    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    public Observation receive(@RequestBody Observation data) {
        if (data == null || data.deviceId == null || data.deviceId.isBlank()
                || !Double.isFinite(data.latitude) || !Double.isFinite(data.longitude)
                || data.latitude < -90 || data.latitude > 90
                || data.longitude < -180 || data.longitude > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tracking observation");
        }

        boolean isNewTrack = !tracks.containsKey(data.deviceId);
        data.receivedAt = Instant.now();
        tracks.put(data.deviceId, data);

        Alert alert = new Alert();
        alert.id = data.deviceId + "-" + data.receivedAt.toEpochMilli();
        alert.level = "INFO";
        alert.title = isNewTrack ? "New Remote ID track received" : "Remote ID track updated";
        alert.message = "A receiver submitted an observation for device " + data.deviceId
                + ". This confirms data receipt only; it does not determine whether the drone is a threat.";
        alert.deviceId = data.deviceId;
        alert.createdAt = data.receivedAt;
        synchronized (alerts) {
            alerts.addFirst(alert);
            while (alerts.size() > 50) alerts.removeLast();
        }
        return data;
    }

    @GetMapping("/tracks")
    public List<Observation> getTracks() {
        return tracks.values().stream()
                .sorted(Comparator.comparing(o -> o.deviceId))
                .toList();
    }

    @GetMapping("/alerts")
    public List<Alert> getAlerts() {
        synchronized (alerts) {
            return new ArrayList<>(alerts);
        }
    }

    @GetMapping("/status")
    public String status() {
        return tracks.isEmpty() ? "NO LIVE REMOTE ID DATA RECEIVED" : "REMOTE ID DATA RECEIVED";
    }
}