package it.unicas.spring.googleapi.demo.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON payload published by the ESP32 via MQTT (topic: omnitrack/bus/gps).
 *
 * Real ESP32 implementation:
 *   - GPS module: Quectel L76K (multi-constellation GNSS) connected via UART
 *   - TinyGPS++ library parses NMEA sentences:
 *       $GNRMC → lat, lon, speed (knots × 1.852 = km/h), bearing (course over ground)
 *       $GNGGA → fix quality, HDOP (estimated accuracy in metres)
 *   - Crowding comes from an IR sensor (e.g. HC-SR501) counting passengers at the door
 *   - Published every 10 s to the MQTT broker via WiFi (Arduino PubSubClient)
 */
public class GpsData {

    @JsonProperty("busId")
    private String busId;

    @JsonProperty("latitude")
    private double latitude;

    @JsonProperty("longitude")
    private double longitude;

    @JsonProperty("timestamp")
    private long timestamp;           // epoch milliseconds UTC

    @JsonProperty("speedKmh")
    private double speedKmh;          // from $GNRMC: knots × 1.852 (read directly by TinyGPS++)

    @JsonProperty("bearing")
    private double bearing;           // course over ground, 0–360° (North = 0)

    @JsonProperty("crowding")
    private int crowding;             // 0 = empty … 10 = full

    @JsonProperty("gpsAccuracyMeters")
    private double gpsAccuracyMeters; // estimated from HDOP — typically 3–8 m in open sky

    /**
     * Index of the next stop in the configured route list.
     * Set deterministically by the simulator (segment-based) to avoid proximity oscillation.
     * -1 = not provided (real ESP32 devices that do not send this field fall back to
     *      server-side proximity search in VehicleStateService).
     */
    @JsonProperty("nextStopIndex")
    private int nextStopIndex = -1;

    public GpsData() {}

    public String getBusId()                   { return busId; }
    public void   setBusId(String busId)       { this.busId = busId; }
    public double getLatitude()                { return latitude; }
    public void   setLatitude(double v)        { this.latitude = v; }
    public double getLongitude()               { return longitude; }
    public void   setLongitude(double v)       { this.longitude = v; }
    public long   getTimestamp()               { return timestamp; }
    public void   setTimestamp(long v)         { this.timestamp = v; }
    public double getSpeedKmh()                { return speedKmh; }
    public void   setSpeedKmh(double v)        { this.speedKmh = v; }
    public double getBearing()                 { return bearing; }
    public void   setBearing(double v)         { this.bearing = v; }
    public int    getCrowding()                { return crowding; }
    public void   setCrowding(int v)           { this.crowding = v; }
    public double getGpsAccuracyMeters()       { return gpsAccuracyMeters; }
    public void   setGpsAccuracyMeters(double v){ this.gpsAccuracyMeters = v; }
    public int    getNextStopIndex()           { return nextStopIndex; }
    public void   setNextStopIndex(int v)      { this.nextStopIndex = v; }

    @Override
    public String toString() {
        return String.format("GpsData{bus=%s lat=%.6f lon=%.6f speed=%.1fkm/h bearing=%.0f° crowding=%d/10}",
                busId, latitude, longitude, speedKmh, bearing, crowding);
    }
}
