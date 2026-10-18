package com.skyshield;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/remote-id")
public class RemoteIdController {

    private final ConcurrentHashMap<String, Observation> tracks =
            new ConcurrentHashMap<>();

    public static class Observation {
        public String deviceId;
        public double latitude;
        public double longitude;
        public Double altitude;
        public Double speed;
        public Double heading;
        public Instant receivedAt;
    }

    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    public Observation receive(@RequestBody Observation data) {
        if (data.deviceId == null || data.deviceId.isBlank()
                || !Double.isFinite(data.latitude)
                || !Double.isFinite(data.longitude)
                || data.latitude < -90 || data.latitude > 90
                || data.longitude < -180 || data.longitude > 180) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid tracking observation");
        }

        data.receivedAt = Instant.now();
        tracks.put(data.deviceId, data);
        return data;
    }

    @GetMapping("/tracks")
    public List<Observation> getTracks() {
        return tracks.values().stream()
                .sorted(Comparator.comparing(o -> o.deviceId))
                .toList();
    }

    @GetMapping("/status")
    public String status() {
        return tracks.isEmpty()
                ? "NO LIVE REMOTE ID DATA RECEIVED"
                : "REMOTE ID DATA RECEIVED";
    }
}