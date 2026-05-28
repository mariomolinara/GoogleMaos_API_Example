package it.unicas.spring.googleapi.demo.controller;

import it.unicas.spring.googleapi.demo.config.BusLineConfig;
import it.unicas.spring.googleapi.demo.config.FleetConfiguration;
import it.unicas.spring.googleapi.demo.model.VehicleState;
import it.unicas.spring.googleapi.demo.service.SiriMessageBuilder;
import it.unicas.spring.googleapi.demo.service.VehicleStateService;
import it.unicas.spring.googleapi.demo.sse.SiriPushService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * REST API for the OmniTrack CassiTrack monitoring system.
 *
 * Endpoints:
 *   GET  /api/omnitrack/status           — current state of all buses in the fleet (JSON array)
 *   GET  /api/omnitrack/status/{busId}   — current state of a specific bus
 *   GET  /api/omnitrack/siri/current     — latest SIRI 2.0 XML for the first available bus
 *   GET  /api/omnitrack/route            — all configured bus lines with their stops
 *   GET  /api/omnitrack/route/{lineId}   — stops for a specific line
 *   GET  /api/omnitrack/health           — system health check
 */
@RestController
@RequestMapping("/api/omnitrack")
@CrossOrigin(origins = "*")
public class OmnitrackController {

    private final VehicleStateService vehicleStateService;
    private final SiriMessageBuilder  siriMessageBuilder;
    private final SiriPushService     siriPushService;
    private final FleetConfiguration  fleetConfig;

    public OmnitrackController(VehicleStateService vehicleStateService,
                                SiriMessageBuilder siriMessageBuilder,
                                SiriPushService siriPushService,
                                FleetConfiguration fleetConfig) {
        this.vehicleStateService = vehicleStateService;
        this.siriMessageBuilder  = siriMessageBuilder;
        this.siriPushService     = siriPushService;
        this.fleetConfig         = fleetConfig;
    }

    /**
     * Returns the current state of every bus in the fleet as a JSON array.
     * Buses whose first GPS message has not yet arrived are omitted from the array.
     */
    @GetMapping("/status")
    public ResponseEntity<List<Map<String, Object>>> getStatus() {
        Map<String, VehicleState> all = vehicleStateService.getAllStates();

        if (all.isEmpty()) {
            // No data yet — return a single "waiting" entry
            Map<String, Object> waiting = new LinkedHashMap<>();
            waiting.put("status",    "waiting");
            waiting.put("message",   "Waiting for first GPS data from the ESP32 fleet…");
            waiting.put("timestamp", Instant.now().toString());
            return ResponseEntity.ok(List.of(waiting));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (VehicleState state : all.values()) {
            result.add(buildBusStatus(state));
        }
        return ResponseEntity.ok(result);
    }

    /** Returns the current state of a single bus, identified by its busId. */
    @GetMapping("/status/{busId}")
    public ResponseEntity<Map<String, Object>> getStatusByBusId(@PathVariable String busId) {
        Map<String, VehicleState> all = vehicleStateService.getAllStates();
        VehicleState state = all.get(busId);
        if (state == null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status",  "not_found");
            r.put("busId",   busId);
            r.put("message", "No GPS data received yet for bus " + busId);
            return ResponseEntity.ok(r);
        }
        return ResponseEntity.ok(buildBusStatus(state));
    }

    /**
     * Returns the latest SIRI 2.0 VehicleMonitoring XML for the first available bus.
     * For per-bus SIRI XML, subscribe to the SSE stream at /api/siri/stream.
     */
    @GetMapping(value = "/siri/current", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getCurrentSiriMessage() {
        VehicleState state = vehicleStateService.getCurrentState();
        if (state == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(siriMessageBuilder.buildVehicleMonitoringDelivery(state));
    }

    /** Returns all configured bus lines with their ordered stops. */
    @GetMapping("/route")
    public ResponseEntity<Map<String, Object>> getRoute() {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (BusLineConfig line : fleetConfig.getBuses()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("busId",      line.getBusId());
            entry.put("lineId",     line.getLineId());
            entry.put("lineName",   line.getLineName());
            entry.put("operator",   line.getOperator());
            entry.put("direction",  line.getDirection());
            entry.put("journeyRef", line.getJourneyRef());
            entry.put("stops",      line.getStops());
            lines.add(entry);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalLines", lines.size());
        response.put("lines",      lines);
        return ResponseEntity.ok(response);
    }

    /** Returns the stops for a specific bus line identified by lineId. */
    @GetMapping("/route/{lineId}")
    public ResponseEntity<Map<String, Object>> getRouteByLineId(@PathVariable String lineId) {
        return fleetConfig.getBuses().stream()
                .filter(l -> lineId.equals(l.getLineId()))
                .findFirst()
                .map(line -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("busId",      line.getBusId());
                    r.put("lineId",     line.getLineId());
                    r.put("lineName",   line.getLineName());
                    r.put("operator",   line.getOperator());
                    r.put("direction",  line.getDirection());
                    r.put("journeyRef", line.getJourneyRef());
                    r.put("stops",      line.getStops());
                    return ResponseEntity.ok(r);
                })
                .orElse(ResponseEntity.notFound().<Map<String, Object>>build());
    }

    /** System health check — verifies all components are running. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, VehicleState> all = vehicleStateService.getAllStates();
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("service",          "omnitrack-cassitrack");
        health.put("status",           "UP");
        health.put("mqttBroker",       "active");
        health.put("esp32Simulator",   "running");
        health.put("configuredBuses",  fleetConfig.getBuses().size());
        health.put("activeBuses",      all.size());
        health.put("activeSseClients", siriPushService.getActiveEmitterCount());
        health.put("timestamp",        Instant.now().toString());
        return ResponseEntity.ok(health);
    }

    // ---- helpers ----

    private Map<String, Object> buildBusStatus(VehicleState state) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status",       "active");
        r.put("busId",        state.getBusId());
        r.put("lineId",       state.getLineId());
        r.put("lineName",     state.getLineName());
        r.put("latitude",     state.getLatitude());
        r.put("longitude",    state.getLongitude());
        r.put("speedKmh",     state.getSpeedKmh());
        r.put("bearing",      state.getBearing());
        r.put("crowding",     state.getCrowding());
        r.put("occupancySiri",state.getOccupancySiri());
        r.put("lastGpsUpdate",state.getLastGpsUpdate() != null
                ? state.getLastGpsUpdate().toString() : null);

        if (state.getNextStop() != null) {
            Map<String, Object> ns = new LinkedHashMap<>();
            ns.put("id",        state.getNextStop().getId());
            ns.put("name",      state.getNextStop().getName());
            ns.put("latitude",  state.getNextStop().getLatitude());
            ns.put("longitude", state.getNextStop().getLongitude());
            ns.put("order",     state.getNextStop().getOrder());
            r.put("nextStop", ns);
        }

        int etaSec = state.getEtaToNextStopSeconds();
        r.put("etaToNextStopSeconds", etaSec);
        r.put("etaToNextStopMinutes", etaSec / 60);
        r.put("etaFromGoogleMaps",    state.isEtaFromGoogleMaps());
        r.put("etaArrivalTime",       Instant.now().plusSeconds(etaSec).toString());
        r.put("activeSseClients",     siriPushService.getActiveEmitterCount());
        r.put("timestamp",            Instant.now().toString());
        return r;
    }
}
