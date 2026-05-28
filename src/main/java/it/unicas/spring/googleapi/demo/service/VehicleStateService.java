package it.unicas.spring.googleapi.demo.service;

import it.unicas.spring.googleapi.demo.config.BusLineConfig;
import it.unicas.spring.googleapi.demo.config.FleetConfiguration;
import it.unicas.spring.googleapi.demo.model.GpsData;
import it.unicas.spring.googleapi.demo.model.RouteStop;
import it.unicas.spring.googleapi.demo.model.VehicleState;
import it.unicas.spring.googleapi.demo.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core service that maintains the real-time state of all vehicles in the fleet.
 *
 * Each time a GPS message arrives from an ESP32 (or the simulator) via MQTT:
 *   1. The incoming bus ID is resolved to a BusLineConfig (route + SIRI metadata)
 *   2. The next stop is determined (from the GPS payload or by proximity search)
 *   3. ETA is requested from GoogleMapsService (falls back to Haversine if no API key)
 *   4. A new VehicleState is atomically stored in the concurrent map keyed by busId
 *
 * Thread-safety: ConcurrentHashMap allows concurrent reads from the SSE publisher
 * and REST controller while the MQTT subscriber thread writes.
 */
@Service
public class VehicleStateService {

    private static final Logger log = LoggerFactory.getLogger(VehicleStateService.class);

    private final FleetConfiguration fleetConfig;
    private final GoogleMapsService  googleMapsService;

    /** Live state for every bus in the fleet. Key = busId. */
    private final ConcurrentHashMap<String, VehicleState> states = new ConcurrentHashMap<>();

    public VehicleStateService(FleetConfiguration fleetConfig, GoogleMapsService googleMapsService) {
        this.fleetConfig       = fleetConfig;
        this.googleMapsService = googleMapsService;
    }

    /**
     * Main entry point: receives a decoded GPS payload from an ESP32 via MQTT
     * and updates the live state of that vehicle.
     */
    public void processGpsData(GpsData gps) {

        // Resolve route configuration for this bus
        BusLineConfig line = fleetConfig.findByBusId(gps.getBusId())
                .orElseGet(() -> fleetConfig.getBuses().isEmpty() ? null : fleetConfig.getBuses().get(0));

        if (line == null) {
            log.warn("No route configured for bus '{}'. Message ignored.", gps.getBusId());
            return;
        }

        List<RouteStop> stops = line.getStops();
        if (stops == null || stops.isEmpty()) {
            log.warn("No stops configured for bus '{}' on line '{}'.", gps.getBusId(), line.getLineId());
            return;
        }

        // Determine the next stop:
        //   - prefer the index provided by the ESP32/simulator (deterministic, segment-based)
        //   - fall back to nearest-stop proximity search for real devices that omit this field
        int nextStopIdx;
        int gpsNextIdx = gps.getNextStopIndex();
        if (gpsNextIdx >= 0 && gpsNextIdx < stops.size()) {
            nextStopIdx = gpsNextIdx;
        } else {
            nextStopIdx = findNextStopIndex(gps.getLatitude(), gps.getLongitude(), stops);
        }
        RouteStop nextStop = stops.get(nextStopIdx);

        // Compute ETA (Google Maps Directions API or Haversine fallback)
        boolean apiConfigured = googleMapsService.isApiKeyConfigured();
        int eta = googleMapsService.getEtaToStop(
                gps.getLatitude(), gps.getLongitude(), nextStop, gps.getSpeedKmh());

        // Build and store the new vehicle state
        VehicleState state = new VehicleState();
        state.setBusId(gps.getBusId());
        state.setLatitude(gps.getLatitude());
        state.setLongitude(gps.getLongitude());
        state.setSpeedKmh(gps.getSpeedKmh());
        state.setBearing(gps.getBearing());
        state.setCrowding(gps.getCrowding());
        state.setLastGpsUpdate(Instant.ofEpochMilli(gps.getTimestamp()));
        state.setNextStop(nextStop);
        state.setEtaToNextStopSeconds(eta);
        state.setCurrentStopIndex(nextStopIdx);
        state.setOccupancySiri(VehicleState.crowdingToSiriOccupancy(gps.getCrowding()));
        state.setEtaFromGoogleMaps(apiConfigured);

        // Populate SIRI route metadata from the fleet configuration
        state.setLineId(line.getLineId());
        state.setLineName(line.getLineName());
        state.setOperator(line.getOperator());
        state.setDirection(line.getDirection());
        state.setJourneyRef(line.getJourneyRef());

        states.put(gps.getBusId(), state);

        log.info("State updated: bus={} line={} pos=[{},{}] next='{}' ETA={}s (~{}min)",
                gps.getBusId(), line.getLineId(),
                String.format("%.6f", gps.getLatitude()),
                String.format("%.6f", gps.getLongitude()),
                nextStop.getName(), eta, eta / 60);
    }

    /**
     * Returns an unmodifiable snapshot of all current vehicle states, keyed by busId.
     * Used by SiriPushService and OmnitrackController to serve the full fleet.
     */
    public Map<String, VehicleState> getAllStates() {
        return Collections.unmodifiableMap(states);
    }

    /**
     * Returns the state of the first available bus, or null if no GPS data received yet.
     * Kept for backward compatibility with single-bus callers.
     */
    public VehicleState getCurrentState() {
        return states.values().stream().findFirst().orElse(null);
    }

    /**
     * Proximity-based fallback: finds the index of the closest stop to the current position.
     * If within 50 m of that stop and it is not the last one, advances to the next stop.
     *
     * NOTE: This can oscillate near stop boundaries. Prefer the deterministic nextStopIndex
     * field provided by the ESP32 simulator whenever available.
     */
    private int findNextStopIndex(double lat, double lon, List<RouteStop> stops) {
        int bestIdx = 0;
        double bestDist = Double.MAX_VALUE;

        for (int i = 0; i < stops.size(); i++) {
            RouteStop s = stops.get(i);
            double dist = GeoUtils.distanceMeters(lat, lon, s.getLatitude(), s.getLongitude());
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx  = i;
            }
        }

        // If very close to the nearest stop, consider it already reached and advance
        if (bestDist < 50.0 && bestIdx < stops.size() - 1) {
            bestIdx++;
        }

        return Math.min(bestIdx, stops.size() - 1);
    }
}

