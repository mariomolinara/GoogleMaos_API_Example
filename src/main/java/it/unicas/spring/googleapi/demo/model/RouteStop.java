package it.unicas.spring.googleapi.demo.model;

/**
 * Rappresenta una fermata del percorso bus.
 * Le coordinate sono in WGS84 (standard GPS/GNSS).
 */
public class RouteStop {

    private String id;           // es. "STOP-001"
    private String name;         // es. "Stazione FS Cassino"
    private double latitude;     // gradi decimali Nord
    private double longitude;    // gradi decimali Est
    private int order;           // posizione nella sequenza del percorso

    public RouteStop() {}

    public RouteStop(String id, String name, double latitude, double longitude, int order) {
        this.id = id;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.order = order;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }

    @Override
    public String toString() {
        return String.format("RouteStop{id='%s', name='%s', lat=%.6f, lon=%.6f, order=%d}",
                id, name, latitude, longitude, order);
    }
}

