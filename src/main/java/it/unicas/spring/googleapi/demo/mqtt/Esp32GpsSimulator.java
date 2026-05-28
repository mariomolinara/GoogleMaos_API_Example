package it.unicas.spring.googleapi.demo.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unicas.spring.googleapi.demo.config.RouteConfiguration;
import it.unicas.spring.googleapi.demo.model.GpsData;
import it.unicas.spring.googleapi.demo.model.RouteStop;
import it.unicas.spring.googleapi.demo.util.GeoUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.DependsOn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * Simulatore ESP32 con modulo GPS.
 *
 * Simula un ESP32 montato su un autobus che:
 *  1. Legge posizione GPS (NMEA da u-blox NEO-6M via UART2)
 *  2. Calcola velocita' dalla frase $GPRMC (knots * 1.852 = km/h)
 *  3. Conta passeggeri con sensore IR sul portellone (crowding 0-10)
 *  4. Pubblica JSON via MQTT ogni 10 secondi
 *
 * Il bus percorre il tragitto interpolando linearmente tra le fermate
 * definite in application.properties.
 *
 * === CODICE EQUIVALENTE ESP32 (Arduino/PubSubClient) ===
 * void loop() {
 *   if (gps.encode(GPSSerial.read())) {
 *     if (gps.location.isValid()) {
 *       String payload = "{\"busId\":\"" + BUS_ID + "\"," +
 *         "\"latitude\":" + gps.location.lat() + "," +
 *         "\"longitude\":" + gps.location.lng() + "," +
 *         "\"timestamp\":" + millis() + "," +
 *         "\"speedKmh\":" + gps.speed.kmph() + "," +
 *         "\"bearing\":" + gps.course.deg() + "," +
 *         "\"crowding\":" + getCrowdingLevel() + "}";
 *       mqttClient.publish("omnitrack/bus/gps", payload.c_str());
 *     }
 *   }
 * }
 */
@Component
@DependsOn("embeddedMqttBroker")
public class Esp32GpsSimulator {

    private static final Logger log = LoggerFactory.getLogger(Esp32GpsSimulator.class);

    // Numero di step di simulazione tra una fermata e la successiva
    private static final int STEPS_PER_SEGMENT = 20;

    @Value("${mqtt.broker.host:localhost}")
    private String brokerHost;

    @Value("${mqtt.broker.port:1883}")
    private int brokerPort;

    @Value("${mqtt.topic.gps:omnitrack/bus/gps}")
    private String gpsTopic;

    @Value("${mqtt.client.publisher.id:esp32-sim}")
    private String publisherClientId;

    @Value("${simulator.bus.id:BUS-CASSINO-001}")
    private String busId;

    @Value("${simulator.enabled:true}")
    private boolean simulatorEnabled;

    private final RouteConfiguration routeConfig;
    private final ObjectMapper objectMapper;

    private MqttClient mqttPublisher;
    private int currentStep = 0;       // step corrente nella sequenza del tragitto
    private int totalSteps = 0;        // numero totale di step
    private double prevLat, prevLon;   // posizione precedente (per calcolo bearing real-time)
    private final Random random = new Random();

    // Traiettoria completa: lista di coordinate interpolate
    private double[] routeLats;
    private double[] routeLons;

    public Esp32GpsSimulator(RouteConfiguration routeConfig, ObjectMapper objectMapper) {
        this.routeConfig = routeConfig;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        if (!simulatorEnabled) {
            log.info("Simulatore ESP32 disabilitato (simulator.enabled=false).");
            return;
        }

        // Costruisci traiettoria interpolata tra le fermate
        buildRoute();

        // Connetti il publisher MQTT
        try {
            String brokerUrl = "tcp://" + brokerHost + ":" + brokerPort;
            mqttPublisher = new MqttClient(brokerUrl, publisherClientId, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            mqttPublisher.connect(opts);
            log.info(">>> Simulatore ESP32 connesso a {} - pubblicazione su topic: {}", brokerUrl, gpsTopic);
        } catch (MqttException e) {
            log.error("Errore connessione MQTT publisher (simulatore): {}", e.getMessage(), e);
        }
    }

    /**
     * Genera la sequenza di coordinate interpolate lungo il percorso.
     */
    private void buildRoute() {
        List<RouteStop> stops = routeConfig.getStops();
        if (stops == null || stops.size() < 2) {
            log.warn("Fermate insufficienti per costruire il percorso (min 2). Verifica application.properties.");
            routeLats = new double[]{41.4912};
            routeLons = new double[]{13.8306};
            totalSteps = 1;
            return;
        }

        int segments = stops.size() - 1;
        totalSteps = segments * STEPS_PER_SEGMENT;
        routeLats = new double[totalSteps];
        routeLons = new double[totalSteps];

        int idx = 0;
        for (int s = 0; s < segments; s++) {
            RouteStop from = stops.get(s);
            RouteStop to   = stops.get(s + 1);
            for (int step = 0; step < STEPS_PER_SEGMENT; step++) {
                double t = (double) step / STEPS_PER_SEGMENT;
                routeLats[idx] = GeoUtils.interpolate(from.getLatitude(),  to.getLatitude(),  t);
                routeLons[idx] = GeoUtils.interpolate(from.getLongitude(), to.getLongitude(), t);
                idx++;
            }
        }

        // Punto di partenza per calcolo bearing iniziale
        prevLat = routeLats[0];
        prevLon = routeLons[0];

        log.info("Percorso simulato: {} tappe, {} step totali ({}s ciascuno)",
                stops.size(), totalSteps, 10);
    }

    /**
     * Pubblicazione MQTT ogni 10 secondi (configurabile via simulator.update.interval.ms).
     * Simula l'ESP32 che invia la posizione GPS corrente.
     */
    @Scheduled(fixedRateString = "${simulator.update.interval.ms:10000}",
               initialDelay   = 3000)       // attende 3s all'avvio
    public void publishGpsPosition() {
        if (!simulatorEnabled || mqttPublisher == null || !mqttPublisher.isConnected()) return;
        if (totalSteps == 0) return;

        double lat = routeLats[currentStep];
        double lon = routeLons[currentStep];

        // Calcolo bearing reale dalla posizione precedente
        double bearing = 0.0;
        if (currentStep > 0) {
            bearing = GeoUtils.bearingDegrees(prevLat, prevLon, lat, lon);
        }

        // Calcolo velocita' in km/h (distanza / tempo, come farebbe il GPS NMEA $GPRMC)
        double distMeters = GeoUtils.distanceMeters(prevLat, prevLon, lat, lon);
        double speedKmh = (distMeters / 10.0) * 3.6;  // 10s tra i messaggi
        // Aggiungi rumore realistico (±5%)
        speedKmh = speedKmh * (0.95 + random.nextDouble() * 0.10);
        speedKmh = Math.max(0, Math.min(speedKmh, 80.0));

        // Affollamento: simulato con variazione graduale (sensore porta)
        int crowding = simulateCrowding(currentStep);

        // Accuratezza GPS tipica: 3-8 metri con cielo libero (HDOP < 2)
        double accuracy = 3.0 + random.nextDouble() * 5.0;

        GpsData data = new GpsData();
        data.setBusId(busId);
        data.setLatitude(lat);
        data.setLongitude(lon);
        data.setTimestamp(System.currentTimeMillis());
        data.setSpeedKmh(Math.round(speedKmh * 10.0) / 10.0);
        data.setBearing(Math.round(bearing * 10.0) / 10.0);
        data.setCrowding(crowding);
        data.setGpsAccuracyMeters(Math.round(accuracy * 10.0) / 10.0);

        try {
            String json = objectMapper.writeValueAsString(data);
            MqttMessage msg = new MqttMessage(json.getBytes());
            msg.setQos(1);
            msg.setRetained(false);
            mqttPublisher.publish(gpsTopic, msg);
            log.info("[ESP32 -> MQTT] step={}/{} lat=%.6f lon=%.6f speed=%.1fkm/h crowding={}".formatted(
                    currentStep + 1, totalSteps, lat, lon, data.getSpeedKmh(), data.getCrowding()));
        } catch (Exception e) {
            log.error("Errore pubblicazione MQTT: {}", e.getMessage(), e);
        }

        // Aggiorna posizione precedente e avanza al prossimo step (circolare)
        prevLat = lat;
        prevLon = lon;
        currentStep = (currentStep + 1) % totalSteps;
    }

    /**
     * Simula il livello di affollamento in base alla posizione nel percorso.
     * In un sistema reale, questo valore viene dal contatore di passeggeri a bordo.
     */
    private int simulateCrowding(int step) {
        double progress = (double) step / totalSteps;
        // Bus si riempie a meta' percorso, si svuota verso la fine
        int base = (int) (10 * 4 * progress * (1 - progress)); // campana gaussiana
        return Math.max(0, Math.min(10, base + random.nextInt(3) - 1));
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (mqttPublisher != null && mqttPublisher.isConnected()) {
                mqttPublisher.disconnect();
                log.info("Simulatore ESP32 disconnesso.");
            }
        } catch (MqttException e) {
            log.error("Errore disconnessione simulatore: {}", e.getMessage());
        }
    }
}

