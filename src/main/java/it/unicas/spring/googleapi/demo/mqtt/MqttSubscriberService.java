package it.unicas.spring.googleapi.demo.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unicas.spring.googleapi.demo.model.GpsData;
import it.unicas.spring.googleapi.demo.service.VehicleStateService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.DependsOn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Subscriber MQTT (Eclipse Paho v3).
 * Si connette al broker embedded e resta in ascolto sul topic GPS.
 * Ogni messaggio ricevuto viene deserializzato da JSON (GpsData)
 * e passato al VehicleStateService per l'elaborazione.
 *
 * Questo simula il server OmniTrack che riceve i dati dall'ESP32.
 */
@Component
@DependsOn("embeddedMqttBroker")
public class MqttSubscriberService {

    private static final Logger log = LoggerFactory.getLogger(MqttSubscriberService.class);

    @Value("${mqtt.broker.host:localhost}")
    private String brokerHost;

    @Value("${mqtt.broker.port:1883}")
    private int brokerPort;

    @Value("${mqtt.topic.gps:omnitrack/bus/gps}")
    private String gpsTopic;

    @Value("${mqtt.client.subscriber.id:omnitrack-server}")
    private String clientId;

    private final VehicleStateService vehicleStateService;
    private final ObjectMapper objectMapper;

    private MqttClient mqttClient;

    public MqttSubscriberService(VehicleStateService vehicleStateService, ObjectMapper objectMapper) {
        this.vehicleStateService = vehicleStateService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void connect() {
        try {
            String brokerUrl = "tcp://" + brokerHost + ":" + brokerPort;
            mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);

            mqttClient.setCallback(new MqttCallback() {

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("Connessione MQTT persa: {}. Riconnessione automatica...", cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    handleGpsMessage(topic, message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // non usato nel subscriber
                }
            });

            mqttClient.connect(options);
            // Sottoscrivi con wildcard per ricevere tutti i bus
            mqttClient.subscribe(gpsTopic, 1);

            log.info(">>> MQTT Subscriber connesso a {} - in ascolto su topic: {}", brokerUrl, gpsTopic);

        } catch (MqttException e) {
            log.error("Errore connessione MQTT subscriber: {}", e.getMessage(), e);
        }
    }

    private void handleGpsMessage(String topic, byte[] payload) {
        try {
            String json = new String(payload);
            log.debug("MQTT ricevuto [{}]: {}", topic, json);

            GpsData gpsData = objectMapper.readValue(json, GpsData.class);
            log.info("GPS ricevuto da ESP32 → {}", gpsData);

            // Elaborazione: aggiorna stato veicolo, calcola ETA, ecc.
            vehicleStateService.processGpsData(gpsData);

        } catch (Exception e) {
            log.error("Errore parsing messaggio GPS MQTT: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                log.info("MQTT Subscriber disconnesso.");
            }
        } catch (MqttException e) {
            log.error("Errore disconnessione MQTT: {}", e.getMessage());
        }
    }
}

