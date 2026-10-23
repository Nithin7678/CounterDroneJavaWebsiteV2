# SKYSHIELD AI — Counter-Drone Awareness Dashboard

Java 17 + Spring Boot web application for an academic airspace-awareness project.

## Current capabilities
- OpenSky Network aircraft state-vector feed through the Java backend.
- Map markers and a live aircraft table for the configured Bengaluru-area bounding box.
- Highlights returned aviation squawk codes 7500, 7600, and 7700.
- REST endpoint for compatible Remote ID receivers to submit observations:
  - `POST /api/remote-id/observations`
  - `GET /api/remote-id/tracks`
  - `GET /api/remote-id/alerts`
- Browser dashboard refreshes the OpenSky view periodically.

## Important limitations
OpenSky is an aircraft ADS-B state feed, not a dedicated drone Remote ID feed. It does not prove that a plotted aircraft is a drone. Remote ID endpoints only show data when a compatible receiver or authorized sender actually submits observations. This application does not connect to a physical receiver by itself and does not intercept or disable drones.

## Run locally
Requires Java 17 and Maven.

```bash
mvn spring-boot:run
```

Open http://localhost:8080

## Deploy on Render
Create a Web Service from this GitHub repository and select branch `skyshield-clean-build`.

- Runtime: Docker (use the included Dockerfile), or Java with build command `mvn clean package` and start command `java -Dserver.port=$PORT -jar target/skyshield-ai-1.0.0.jar`.
- Java version: 17.
- Optional environment variables: `OPENSKY_CLIENT_ID`, `OPENSKY_CLIENT_SECRET`.
- Never commit API credentials or passwords into source files.

## Endpoints
- `GET /api/status`
- `GET /api/opensky/alerts`
- `POST /api/remote-id/observations`
- `GET /api/remote-id/tracks`
- `GET /api/remote-id/alerts`

This is an academic situational-awareness project. Verify any aviation concern with official authorities; do not use this dashboard as a sole basis for operational decisions.