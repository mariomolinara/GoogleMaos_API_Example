package it.unicas.spring.googleapi.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unicas.spring.googleapi.demo.model.RouteStop;
import it.unicas.spring.googleapi.demo.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Servizio che chiama la Google Maps Directions API per calcolare
 * il tempo stimato di arrivo (ETA) alla prossima fermata.
 *
 * Endpoint: GET https://maps.googleapis.com/maps/api/directions/json
 *   ?origin={lat},{lon}
 *   &destination={stopLat},{stopLon}
 *   &mode=driving
 *   &departure_time=now
 *   &key={apiKey}
 *
 * La risposta contiene:
 *   routes[0].legs[0].duration_in_traffic.value  (secondi, richiede departure_time)
 *   routes[0].legs[0].duration.value             (secondi, senza traffico)
 *
 * Se la chiave API non e' configurata o la chiamata fallisce,
 * viene restituita una stima basata su distanza/velocita' (Haversine).
 */
@Service
public class GoogleMapsService {

    private static final Logger log = LoggerFactory.getLogger(GoogleMapsService.class);

    @Value("${google.maps.api.key:YOUR_GOOGLE_MAPS_API_KEY}")
    private String apiKey;

    @Value("${google.maps.directions.url:https://maps.googleapis.com/maps/api/directions/json}")
    private String directionsUrl;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GoogleMapsService(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Calcola ETA in secondi dalla posizione corrente alla fermata destinazione.
     *
     * @param currentLat  latitudine corrente del bus
     * @param currentLon  longitudine corrente del bus
     * @param nextStop    prossima fermata del percorso
     * @param speedKmh    velocita' corrente (usata per stima fallback)
     * @return ETA in secondi
     */
    public int getEtaToStop(double currentLat, double currentLon,
                             RouteStop nextStop, double speedKmh) {

        if (isApiKeyConfigured()) {
            try {
                return callGoogleMapsApi(currentLat, currentLon, nextStop);
            } catch (Exception e) {
                log.warn("Google Maps API non disponibile ({}), uso stima Haversine.", e.getMessage());
            }
        } else {
            log.debug("Google Maps API key non configurata - uso stima Haversine.");
        }

        // Fallback: stima geometrica
        double distance = GeoUtils.distanceMeters(currentLat, currentLon,
                nextStop.getLatitude(), nextStop.getLongitude());
        return GeoUtils.estimateEtaSeconds(distance, speedKmh);
    }

    private int callGoogleMapsApi(double lat, double lon, RouteStop stop) {
        String url = String.format(
                "%s?origin=%.6f,%.6f&destination=%.6f,%.6f&mode=driving&departure_time=now&key=%s",
                directionsUrl, lat, lon,
                stop.getLatitude(), stop.getLongitude(),
                apiKey);

        String responseBody = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);

        if (responseBody == null || responseBody.isBlank()) {
            throw new RestClientException("Risposta vuota da Google Maps API");
        }

        JsonNode root  = parseJson(responseBody);
        String status  = root.path("status").asText();

        if (!"OK".equals(status)) {
            throw new RestClientException("Google Maps API status: " + status
                    + " - " + root.path("error_message").asText(""));
        }

        JsonNode leg = root.path("routes").get(0).path("legs").get(0);

        // Preferisci durata nel traffico se disponibile
        JsonNode durationInTraffic = leg.path("duration_in_traffic");
        if (!durationInTraffic.isMissingNode() && durationInTraffic.has("value")) {
            int eta = durationInTraffic.path("value").asInt();
            log.info("ETA Google Maps (traffico) verso '{}': {}s ({} min)",
                    stop.getName(), eta, eta / 60);
            return eta;
        }

        int eta = leg.path("duration").path("value").asInt();
        log.info("ETA Google Maps verso '{}': {}s ({} min)", stop.getName(), eta, eta / 60);
        return eta;
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RestClientException("Errore parsing risposta Google Maps: " + e.getMessage());
        }
    }

    public boolean isApiKeyConfigured() {
        return apiKey != null
                && !apiKey.isBlank()
                && !apiKey.equals("YOUR_GOOGLE_MAPS_API_KEY");
    }
}

