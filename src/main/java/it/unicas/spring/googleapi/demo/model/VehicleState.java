package it.unicas.spring.googleapi.demo.model;

import java.time.Instant;

/**
 * Current state of a bus vehicle, updated each time a new GPS message
 * arrives via MQTT from the ESP32 (or the simulator).
 *
 * Instances are stored inside VehicleStateService in a thread-safe ConcurrentHashMap
 * keyed by busId. Multiple threads may read concurrently (SSE publisher, REST controller)
 * while the MQTT subscriber thread writes.
 */
public class VehicleState {

    // ---- Vehicle position ----
    private String  busId;
    private double  latitude;
    private double  longitude;
    private double  speedKmh;
    private double  bearing;
    private int     crowding;               // 0 = empty .. 10 = full
    private Instant lastGpsUpdate;

    // ---- Route / SIRI metadata (resolved from FleetConfiguration) ----
    private String lineId;
    private String lineName;
    private String operator;
    private String direction;
    private String journeyRef;

    // ---- Next stop & ETA ----
    private RouteStop nextStop;             // next scheduled stop
    private int  etaToNextStopSeconds;      // ETA computed by GoogleMapsService or Haversine fallback
    private int  currentStopIndex;          // index of the next stop in the route list
    private String occupancySiri;           // SIRI occupancy enum: seatsAvailable, standingRoomOnly, full…
    private boolean etaFromGoogleMaps;      // true if ETA came from the real API, false if Haversine estimate

    public VehicleState() {}

    // ---- Getters / Setters ----

    public String getBusId()                          { return busId; }
    public void   setBusId(String busId)              { this.busId = busId; }

    public double getLatitude()                       { return latitude; }
    public void   setLatitude(double latitude)        { this.latitude = latitude; }

    public double getLongitude()                      { return longitude; }
    public void   setLongitude(double longitude)      { this.longitude = longitude; }

    public double getSpeedKmh()                       { return speedKmh; }
    public void   setSpeedKmh(double speedKmh)        { this.speedKmh = speedKmh; }

    public double getBearing()                        { return bearing; }
    public void   setBearing(double bearing)          { this.bearing = bearing; }

    public int  getCrowding()                         { return crowding; }
    public void setCrowding(int crowding)             { this.crowding = crowding; }

    public Instant getLastGpsUpdate()                 { return lastGpsUpdate; }
    public void    setLastGpsUpdate(Instant t)        { this.lastGpsUpdate = t; }

    public String getLineId()                         { return lineId; }
    public void   setLineId(String lineId)            { this.lineId = lineId; }

    public String getLineName()                       { return lineName; }
    public void   setLineName(String lineName)        { this.lineName = lineName; }

    public String getOperator()                       { return operator; }
    public void   setOperator(String operator)        { this.operator = operator; }

    public String getDirection()                      { return direction; }
    public void   setDirection(String direction)      { this.direction = direction; }

    public String getJourneyRef()                     { return journeyRef; }
    public void   setJourneyRef(String journeyRef)    { this.journeyRef = journeyRef; }

    public RouteStop getNextStop()                    { return nextStop; }
    public void      setNextStop(RouteStop nextStop)  { this.nextStop = nextStop; }

    public int  getEtaToNextStopSeconds()             { return etaToNextStopSeconds; }
    public void setEtaToNextStopSeconds(int eta)      { this.etaToNextStopSeconds = eta; }

    public int  getCurrentStopIndex()                 { return currentStopIndex; }
    public void setCurrentStopIndex(int idx)          { this.currentStopIndex = idx; }

    public String getOccupancySiri()                  { return occupancySiri; }
    public void   setOccupancySiri(String v)          { this.occupancySiri = v; }

    public boolean isEtaFromGoogleMaps()              { return etaFromGoogleMaps; }
    public void    setEtaFromGoogleMaps(boolean v)    { this.etaFromGoogleMaps = v; }

    /**
     * Maps the ESP32 crowding level (0-10) to the SIRI 2.0 Occupancy enumeration.
     */
    public static String crowdingToSiriOccupancy(int crowding) {
        if (crowding <= 2) return "manySeatsAvailable";
        if (crowding <= 5) return "seatsAvailable";
        if (crowding <= 7) return "standingRoomOnly";
        if (crowding <= 9) return "full";
        return "notAcceptingPassengers";
    }
}
