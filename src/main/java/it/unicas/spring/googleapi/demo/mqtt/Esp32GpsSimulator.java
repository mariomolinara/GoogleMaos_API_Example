package it.unicas.spring.googleapi.demo.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unicas.spring.googleapi.demo.config.BusLineConfig;
import it.unicas.spring.googleapi.demo.config.FleetConfiguration;
import it.unicas.spring.googleapi.demo.model.GpsData;
import it.unicas.spring.googleapi.demo.model.RouteStop;
import it.unicas.spring.googleapi.demo.util.GeoUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * ESP32 GPS Simulator — substitutes real hardware during development and demos.
 *
 * Simulates one bus per line defined in FleetConfiguration (application.properties).
 * Each bus travels along its route by linearly interpolating between consecutive stops.
 * Every 10 seconds (configurable) all buses publish a JSON GPS payload to the MQTT broker.
 *
 * The published JSON matches the payload that the real Heltec WiFi LoRa 32 V4 +
 * Quectel L76K hardware would send:
 *   { busId, latitude, longitude, timestamp, speedKmh, bearing, crowding,
 *     gpsAccuracyMeters, nextStopIndex }
 *
 * To disable the simulator and use real hardware instead, set:
 *   simulator.enabled=false
 * in application.properties.
 *
 * Equivalent Arduino/PubSubClient sketch (real ESP32):
 *   void loop() {
 *     if (gps.location.isValid()) {
 *       float speed = gps.speed.kmph();   // read from $GNRMC sentence
 *       float bear  = gps.course.deg();   // read from $GNRMC sentence
 *       String payload = buildJson(gps.location.lat(), gps.location.lng(), speed, bear, crowding);
 *       mqttClient.publish("omnitrack/bus/gps", payload.c_str());
 *     }
 *   }
 */
@Component
public class Esp32GpsSimulator {

    private static final Logger log = LoggerFactory.getLogger(Esp32GpsSimulator.class);

    /** Interpolation steps between two consecutive stops. Controls granularity of movement. */
    private static final int STEPS_PER_SEGMENT = 20;

    @Value("${mqtt.broker.host:localhost}")
    private String brokerHost;

    @Value("${mqtt.broker.port:1883}")
    private int brokerPort;

    @Value("${mqtt.topic.gps:omnitrack/bus/gps}")
    private String gpsTopic;

    @Value("${mqtt.client.publisher.id:esp32-simulator-pub}")
    private String publisherClientId;

    @Value("${simulator.enabled:true}")
    private boolean simulatorEnabled;

    private final FleetConfiguration fleetConfig;
    private final ObjectMapper objectMapper;

    private MqttClient mqttPublisher;
    private final Random random = new Random();

    /**
     * Per-bus simulation state — one instance per bus line in the fleet.
     */
    private static class BusSimState {
        final String       busId;
        final BusLineConfig lineConfig;
        int    currentStep        = 0;
        int    totalSteps         = 0;
        double[] routeLats;
        double[] routeLons;
        double prevLat, prevLon;
        int    currentCrowding    = 0;
        int    lastCrowdingSegment = -1;

        BusSimState(String busId, BusLineConfig lineConfig) {
            this.busId      = busId;
            this.lineConfig = lineConfig;
        }
    }

    /** All active bus simulators, keyed by busId. */
    private final Map<String, BusSimState> busStates = new LinkedHashMap<>();

    public Esp32GpsSimulator(FleetConfiguration fleetConfig, ObjectMapper objectMapper) {
        this.fleetConfig  = fleetConfig;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!simulatorEnabled) {
            log.info("ESP32 simulator disabled (simulator.enabled=false).");
            return;
        }

        // Build interpolated trajectory for each bus line in the fleet
        List<BusLineConfig> buses = fleetConfig.getBuses();
        if (buses.isEmpty()) {
            log.warn("No buses configured in fleet. Check application.properties (fleet.buses[*]).");
            return;
        }

        for (int i = 0; i < buses.size(); i++) {
            BusLineConfig line  = buses.get(i);
            BusSimState   state = new BusSimState(line.getBusId(), line);
            buildRoute(state, i, buses.size());
            busStates.put(line.getBusId(), state);
            log.info("Simulator initialized for bus={} line={} ({} steps)",
                    line.getBusId(), line.getLineId(), state.totalSteps);
        }

        // Connect MQTT publisher with retry (broker may still be starting when embedded)
        connectWithRetry();
    }

    /**
     * Builds the interpolated coordinate array for a single bus.
     * Buses are offset in the route so they appear at different positions at startup.
     *
     * @param state   per-bus state to populate
     * @param busIdx  index of this bus in the fleet (used to compute start offset)
     * @param total   total number of buses in the fleet
     */
    private void buildRoute(BusSimState state, int busIdx, int total) {
        List<RouteStop> stops = state.lineConfig.getStops();
        if (stops == null || stops.size() < 2) {
            log.warn("Bus {} has fewer than 2 stops — cannot interpolate route.", state.busId);
            state.routeLats  = new double[]{stops != null && !stops.isEmpty()
                    ? stops.get(0).getLatitude() : 41.4912};
            state.routeLons  = new double[]{stops != null && !stops.isEmpty()
                    ? stops.get(0).getLongitude() : 13.8306};
            state.totalSteps = 1;
            return;
        }

        int segments       = stops.size() - 1;
        state.totalSteps   = segments * STEPS_PER_SEGMENT;
        state.routeLats    = new double[state.totalSteps];
        state.routeLons    = new double[state.totalSteps];

        int idx = 0;
        for (int s = 0; s < segments; s++) {
            RouteStop from = stops.get(s);
            RouteStop to   = stops.get(s + 1);
            for (int step = 0; step < STEPS_PER_SEGMENT; step++) {
                double t = (double) step / STEPS_PER_SEGMENT;
                state.routeLats[idx] = GeoUtils.interpolate(from.getLatitude(),  to.getLatitude(),  t);
                state.routeLons[idx] = GeoUtils.interpolate(from.getLongitude(), to.getLongitude(), t);
                idx++;
            }
        }

        // Stagger start positions so buses appear spread across the route at startup
        state.currentStep = (total > 1) ? (busIdx * state.totalSteps / total) : 0;
        state.prevLat     = state.routeLats[state.currentStep];
        state.prevLon     = state.routeLons[state.currentStep];
    }

    /**
     * Attempts to connect the MQTT publisher with exponential back-off (up to 5 attempts).
     * This avoids the hard @DependsOn("embeddedMqttBroker") which is incompatible
     * with an external Docker broker that may not be a Spring bean.
     */
    private void connectWithRetry() {
        String brokerUrl = "tcp://" + brokerHost + ":" + brokerPort;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                mqttPublisher = new MqttClient(brokerUrl, publisherClientId, new MemoryPersistence());
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setAutomaticReconnect(true);
                opts.setCleanSession(true);
                mqttPublisher.connect(opts);
                log.info(">>> ESP32 Simulator connected to {} — publishing on topic: {}",
                        brokerUrl, gpsTopic);
                return;
            } catch (MqttException e) {
                log.warn("MQTT connect attempt {}/5 failed: {}. Retrying…", attempt, e.getMessage());
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("ESP32 Simulator could not connect to MQTT broker at {}. Simulation disabled.", brokerUrl);
    }

    /**
     * Scheduled GPS publish — fires every `simulator.update.interval.ms` (default 10 s).
     * Each call publishes one MQTT message per bus in the fleet.
     */
    @Scheduled(fixedRateString = "${simulator.update.interval.ms:10000}", initialDelay = 3000)
    public void publishGpsPosition() {
        if (!simulatorEnabled || mqttPublisher == null || !mqttPublisher.isConnected()) return;

        for (BusSimState busState : busStates.values()) {
            publishBusPosition(busState);
        }
    }

    /** Publishes one GPS message for a single bus and advances its step counter. */
    private void publishBusPosition(BusSimState bs) {
        if (bs.totalSteps == 0) return;

        double lat = bs.routeLats[bs.currentStep];
        double lon = bs.routeLons[bs.currentStep];

        // Bearing from the previous position (real GPS gives this via $GNRMC)
        double bearing = (bs.currentStep > 0)
                ? GeoUtils.bearingDegrees(bs.prevLat, bs.prevLon, lat, lon) : 0.0;

        // Speed: Δdistance / Δtime  (real GPS gives this via $GNRMC — speed over ground)
        double distMeters = GeoUtils.distanceMeters(bs.prevLat, bs.prevLon, lat, lon);
        double speedKmh   = (distMeters / 10.0) * 3.6;           // 10 s between messages
        speedKmh = speedKmh * (0.95 + random.nextDouble() * 0.10); // ±5 % noise
        speedKmh = Math.max(0, Math.min(speedKmh, 80.0));

        // Determine current segment and next stop index (deterministic — avoids proximity oscillation)
        int totalSegments  = bs.lineConfig.getStops().size() - 1;
        int currentSegment = (totalSegments > 0) ? (bs.currentStep / STEPS_PER_SEGMENT) : 0;
        int nextStopIdx    = Math.min(currentSegment + 1, bs.lineConfig.getStops().size() - 1);

        // Crowding: updated only when the bus enters a new segment (i.e., reaches a stop)
        if (currentSegment != bs.lastCrowdingSegment) {
            bs.currentCrowding    = simulateCrowding(currentSegment, totalSegments);
            bs.lastCrowdingSegment = currentSegment;
            log.info("[{}] Stop reached → segment {} → crowding updated to {}/10",
                    bs.busId, currentSegment, bs.currentCrowding);
        }

        double accuracy = 3.0 + random.nextDouble() * 5.0; // GPS accuracy 3–8 m (HDOP < 2)

        GpsData data = new GpsData();
        data.setBusId(bs.busId);
        data.setLatitude(lat);
        data.setLongitude(lon);
        data.setTimestamp(System.currentTimeMillis());
        data.setSpeedKmh(Math.round(speedKmh * 10.0) / 10.0);
        data.setBearing(Math.round(bearing * 10.0) / 10.0);
        data.setCrowding(bs.currentCrowding);
        data.setGpsAccuracyMeters(Math.round(accuracy * 10.0) / 10.0);
        data.setNextStopIndex(nextStopIdx);

        try {
            String json = objectMapper.writeValueAsString(data);
            MqttMessage msg = new MqttMessage(json.getBytes());
            msg.setQos(1);
            msg.setRetained(false);
            mqttPublisher.publish(gpsTopic, msg);

            log.info("[{} → MQTT] step={}/{} seg={} lat={} lon={} speed={}km/h bear={}° crowd={}/10 nextStop={}",
                    bs.busId, bs.currentStep + 1, bs.totalSteps, currentSegment,
                    String.format(java.util.Locale.US, "%.6f", lat),
                    String.format(java.util.Locale.US, "%.6f", lon),
                    String.format(java.util.Locale.US, "%.1f", speedKmh),
                    String.format(java.util.Locale.US, "%.1f", bearing),
                    bs.currentCrowding, nextStopIdx);
        } catch (Exception e) {
            log.error("MQTT publish error for bus {}: {}", bs.busId, e.getMessage(), e);
        }

        // Advance step (wraps at end of route → bus loops continuously)
        bs.prevLat    = lat;
        bs.prevLon    = lon;
        bs.currentStep = (bs.currentStep + 1) % bs.totalSteps;
    }

    /**
     * Simulates the crowding level when the bus enters a new route segment (= reaches a stop).
     * Models a bell-curve load: nearly empty at start and end, busiest at mid-route.
     *
     * @param segment       current segment index (0 = first leg)
     * @param totalSegments total number of legs in the route
     * @return crowding level 0–10
     */
    private int simulateCrowding(int segment, int totalSegments) {
        if (totalSegments <= 0) return 0;
        double progress = (double) segment / totalSegments;
        int base = (int) (10 * 4 * progress * (1 - progress)); // bell curve
        return Math.max(0, Math.min(10, base + random.nextInt(3) - 1));
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (mqttPublisher != null && mqttPublisher.isConnected()) {
                mqttPublisher.disconnect();
                log.info("ESP32 simulator disconnected.");
            }
        } catch (MqttException e) {
            log.error("Error disconnecting ESP32 simulator: {}", e.getMessage());
        }
    }
}

