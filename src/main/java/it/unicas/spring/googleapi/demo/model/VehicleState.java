package it.unicas.spring.googleapi.demo.model;

import java.time.Instant;

/**
 * Stato corrente di un veicolo bus, aggiornato ogni volta che arriva
 * un nuovo messaggio GPS via MQTT dall'ESP32.
 */
public class VehicleState {

    private String busId;
    private double latitude;
    private double longitude;
    private double speedKmh;
    private double bearing;
    private int crowding;                     // 0-10
    private Instant lastGpsUpdate;
    private RouteStop nextStop;               // prossima fermata
    private int etaToNextStopSeconds;         // ETA calcolato da Google Maps (o stima)
    private int currentStopIndex;             // indice fermata attuale nel percorso
    private String occupancySiri;             // mapping SIRI: seatsAvailable, standingRoomOnly, full...
    private boolean etaFromGoogleMaps;        // true se ETA da API, false se stimato

    public VehicleState() {}

    // ---- Getters / Setters ----

    public String getBusId() { return busId; }
    public void setBusId(String busId) { this.busId = busId; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(double speedKmh) { this.speedKmh = speedKmh; }

    public double getBearing() { return bearing; }
    public void setBearing(double bearing) { this.bearing = bearing; }

    public int getCrowding() { return crowding; }
    public void setCrowding(int crowding) { this.crowding = crowding; }

    public Instant getLastGpsUpdate() { return lastGpsUpdate; }
    public void setLastGpsUpdate(Instant lastGpsUpdate) { this.lastGpsUpdate = lastGpsUpdate; }

    public RouteStop getNextStop() { return nextStop; }
    public void setNextStop(RouteStop nextStop) { this.nextStop = nextStop; }

    public int getEtaToNextStopSeconds() { return etaToNextStopSeconds; }
    public void setEtaToNextStopSeconds(int etaToNextStopSeconds) { this.etaToNextStopSeconds = etaToNextStopSeconds; }

    public int getCurrentStopIndex() { return currentStopIndex; }
    public void setCurrentStopIndex(int currentStopIndex) { this.currentStopIndex = currentStopIndex; }

    public String getOccupancySiri() { return occupancySiri; }
    public void setOccupancySiri(String occupancySiri) { this.occupancySiri = occupancySiri; }

    public boolean isEtaFromGoogleMaps() { return etaFromGoogleMaps; }
    public void setEtaFromGoogleMaps(boolean etaFromGoogleMaps) { this.etaFromGoogleMaps = etaFromGoogleMaps; }

    /**
     * Mappa il livello crowding (0-10 da ESP32) ai valori di occupancy SIRI 2.0
     */
    public static String crowdingToSiriOccupancy(int crowding) {
        if (crowding <= 2) return "manySeatsAvailable";
        if (crowding <= 5) return "seatsAvailable";
        if (crowding <= 7) return "standingRoomOnly";
        if (crowding <= 9) return "full";
        return "notAcceptingPassengers";
    }
}

