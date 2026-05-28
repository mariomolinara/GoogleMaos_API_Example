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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MQTT subscriber (Eclipse Paho v3).
 *
 * Connects to the MQTT broker (embedded Moquette or external Mosquitto via Docker)
 * and listens on the GPS topic. Each incoming message is deserialized from JSON
 * into a GpsData object and forwarded to VehicleStateService for processing.
 *
 * The @DependsOn annotation has been intentionally removed so that this subscriber
 * works with both the embedded broker (Spring bean) and an external Docker broker
 * (not a Spring bean). Connection retries with exponential back-off handle the case
 * where the broker is not yet available at startup.
 *
 * This simulates the OmniTrack server receiving data from the ESP32 fleet.
 */
@Component
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
        this.objectMapper        = objectMapper;
    }

    @PostConstruct
    public void connect() {
        String brokerUrl = "tcp://" + brokerHost + ":" + brokerPort;

        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setConnectionTimeout(10);
                options.setKeepAliveInterval(30);

                mqttClient.setCallback(new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable cause) {
                        log.warn("MQTT connection lost: {}. Auto-reconnect active.", cause.getMessage());
                    }
                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        handleGpsMessage(topic, message.getPayload());
                    }
                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                        // not used by subscriber
                    }
                });

                mqttClient.connect(options);
                // Wildcard subscription: receives GPS messages from every bus on the topic
                mqttClient.subscribe(gpsTopic, 1);

                log.info(">>> MQTT Subscriber connected to {} — listening on topic: {}", brokerUrl, gpsTopic);
                return;

            } catch (MqttException e) {
                log.warn("MQTT subscriber connect attempt {}/5 failed: {}. Retrying…", attempt, e.getMessage());
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("MQTT Subscriber could not connect to broker at {} after 5 attempts.", brokerUrl);
    }

    private void handleGpsMessage(String topic, byte[] payload) {
        try {
            String json = new String(payload);
            log.debug("MQTT received [{}]: {}", topic, json);

            GpsData gpsData = objectMapper.readValue(json, GpsData.class);
            log.info("GPS received ← {}", gpsData);

            vehicleStateService.processGpsData(gpsData);

        } catch (Exception e) {
            log.error("Error parsing MQTT GPS message: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                log.info("MQTT Subscriber disconnected.");
            }
        } catch (MqttException e) {
            log.error("Error disconnecting MQTT subscriber: {}", e.getMessage());
        }
    }
}
