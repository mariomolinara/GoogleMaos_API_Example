package it.unicas.spring.googleapi.demo.sse;

import it.unicas.spring.googleapi.demo.model.VehicleState;
import it.unicas.spring.googleapi.demo.service.SiriMessageBuilder;
import it.unicas.spring.googleapi.demo.service.VehicleStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Publishes SIRI 2.0 VehicleMonitoring updates to all connected SSE clients
 * and optionally sends an HTTP POST to a remote CassiTrack central server.
 *
 * Delivery modes — these two modes are ALTERNATIVES, not complements:
 *
 *   1. SSE (Server-Sent Events) — PULL model
 *      The CassiTrack server (or a browser dashboard) opens a long-lived HTTP connection
 *      to GET /api/siri/stream and receives events pushed by this service.
 *      Use this when CassiTrack can initiate the connection to OmniTrack.
 *
 *   2. HTTP POST — PUSH model
 *      OmniTrack proactively POSTs the SIRI XML to the URL set in siri.push.url.
 *      Use this when CassiTrack is behind a firewall and cannot accept incoming connections,
 *      or when you need a guaranteed delivery attempt for each update.
 *
 * Both modes can be enabled simultaneously, but in a typical deployment only one is used.
 * Leave siri.push.url empty to disable HTTP POST (SSE only).
 *
 * This service implements the SIRI "Publish/Subscribe" pattern:
 *   Publisher  → OmniTrack (this application)
 *   Subscriber → CassiTrack Central Server (SSE client or HTTP POST receiver)
 */
@Service
public class SiriPushService {

    private static final Logger log = LoggerFactory.getLogger(SiriPushService.class);

    @Value("${siri.push.url:}")
    private String siriPushUrl;

    private final VehicleStateService vehicleStateService;
    private final SiriMessageBuilder  siriMessageBuilder;
    private final RestClient          restClient;

    /** Thread-safe list of active SSE emitters (one per connected client). */
    private final CopyOnWriteArrayList<SseEmitter> activeEmitters = new CopyOnWriteArrayList<>();

    public SiriPushService(VehicleStateService vehicleStateService,
                           SiriMessageBuilder siriMessageBuilder,
                           RestClient restClient) {
        this.vehicleStateService = vehicleStateService;
        this.siriMessageBuilder  = siriMessageBuilder;
        this.restClient          = restClient;
    }

    /**
     * Registers a new SSE emitter for an incoming client connection to /api/siri/stream.
     * The current SIRI state is sent immediately upon registration.
     */
    public SseEmitter registerEmitter() {
        SseEmitter emitter = new SseEmitter(0L); // 0 = no timeout (persistent connection)

        emitter.onCompletion(() -> {
            activeEmitters.remove(emitter);
            log.info("SSE client disconnected. Active emitters: {}", activeEmitters.size());
        });
        emitter.onTimeout(() -> {
            activeEmitters.remove(emitter);
            log.info("SSE client timed out. Active emitters: {}", activeEmitters.size());
        });
        emitter.onError(e -> {
            activeEmitters.remove(emitter);
            log.warn("SSE client error: {}. Active emitters: {}", e.getMessage(), activeEmitters.size());
        });

        activeEmitters.add(emitter);
        log.info("New SSE client connected. Active emitters: {}", activeEmitters.size());

        // Send the current fleet state immediately so the client does not have to wait
        sendCurrentFleetTo(emitter);

        return emitter;
    }

    /**
     * Scheduled task: every `sse.push.interval.ms` (default 60 s) publishes the SIRI state
     * of ALL buses in the fleet to every connected SSE client.
     * One SSE event is sent per bus per cycle.
     *
     * If siri.push.url is configured, the same data is also sent via HTTP POST.
     */
    @Scheduled(fixedRateString = "${sse.push.interval.ms:60000}", initialDelay = 5000)
    public void pushSiriUpdate() {
        Map<String, VehicleState> all = vehicleStateService.getAllStates();
        if (all.isEmpty()) {
            log.debug("No vehicle state available yet. Skipping SIRI push.");
            return;
        }

        for (VehicleState state : all.values()) {
            String siriXml = siriMessageBuilder.buildVehicleMonitoringDelivery(state);
            log.info(">>> SIRI push: bus={} lat={} lon={} ETA={}s — SSE clients: {}",
                    state.getBusId(), state.getLatitude(), state.getLongitude(),
                    state.getEtaToNextStopSeconds(), activeEmitters.size());

            // Mode 1: SSE push to all connected clients
            pushToAllSseEmitters(siriXml, state.getBusId());

            // Mode 2: HTTP POST to the configured CassiTrack server (if enabled)
            if (siriPushUrl != null && !siriPushUrl.isBlank()) {
                pushHttpPost(siriXml);
            }
        }
    }

    /** Sends a SIRI XML event to every active SSE emitter. Removes dead emitters automatically. */
    private void pushToAllSseEmitters(String siriXml, String busId) {
        List<SseEmitter> dead = new ArrayList<>();

        for (SseEmitter emitter : activeEmitters) {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name("siri-vehicle-monitoring")
                                .id(busId)
                                .data(siriXml)
                                .comment("SIRI 2.0 VehicleMonitoring — bus " + busId)
                );
            } catch (IOException e) {
                log.warn("SSE send failed for bus {}: {}. Removing emitter.", busId, e.getMessage());
                dead.add(emitter);
            }
        }

        activeEmitters.removeAll(dead);
    }

    /** Sends the current SIRI state for all buses to a newly connected SSE client. */
    private void sendCurrentFleetTo(SseEmitter emitter) {
        Map<String, VehicleState> all = vehicleStateService.getAllStates();
        if (all.isEmpty()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("info")
                        .data("<!-- OmniTrack CassiTrack: waiting for first GPS data from the ESP32 fleet… -->")
                );
            } catch (IOException e) { /* ignore */ }
            return;
        }
        for (VehicleState state : all.values()) {
            try {
                String xml = siriMessageBuilder.buildVehicleMonitoringDelivery(state);
                emitter.send(SseEmitter.event()
                        .name("siri-vehicle-monitoring")
                        .id(state.getBusId())
                        .data(xml)
                        .comment("Initial state on connect — bus " + state.getBusId())
                );
            } catch (IOException e) {
                log.warn("Error sending initial SIRI state: {}", e.getMessage());
            }
        }
    }

    /**
     * Sends the SIRI XML to the configured remote server via HTTP POST.
     * This is the SIRI "push notification" (or "direct delivery") mode,
     * as opposed to SSE where the client pulls by holding an open connection.
     */
    private void pushHttpPost(String siriXml) {
        try {
            restClient.post()
                    .uri(siriPushUrl)
                    .header("Content-Type",   "application/xml;charset=UTF-8")
                    .header("X-SIRI-Version", "2.0")
                    .header("X-Producer-Ref", "OMNITRACK-CASSITRACK")
                    .body(siriXml)
                    .retrieve()
                    .toBodilessEntity();

            log.info("SIRI HTTP POST sent successfully to: {}", siriPushUrl);
        } catch (Exception e) {
            log.warn("SIRI HTTP POST to {} failed: {}", siriPushUrl, e.getMessage());
        }
    }

    public int getActiveEmitterCount() {
        return activeEmitters.size();
    }
}
