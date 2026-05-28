package it.unicas.spring.googleapi.demo.config;

import it.unicas.spring.googleapi.demo.model.RouteStop;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for a single bus line in the fleet.
 * Loaded automatically from application.properties by {@link FleetConfiguration}.
 *
 * Each bus line defines:
 *   - The vehicle identifier (busId) that the ESP32 embeds in every GPS payload
 *   - SIRI 2.0 route metadata (lineId, lineName, operator, direction, journeyRef)
 *   - The ordered list of stops with WGS84 coordinates
 */
public class BusLineConfig {

    private String busId      = "BUS-001";
    private String lineId     = "LINE-1";
    private String lineName   = "Line 1";
    private String operator   = "COTRAL";
    private String direction  = "OUTBOUND";
    private String journeyRef = "VJ-001";
    private List<RouteStop> stops = new ArrayList<>();

    public String getBusId()                   { return busId; }
    public void   setBusId(String v)           { this.busId = v; }

    public String getLineId()                  { return lineId; }
    public void   setLineId(String v)          { this.lineId = v; }

    public String getLineName()                { return lineName; }
    public void   setLineName(String v)        { this.lineName = v; }

    public String getOperator()                { return operator; }
    public void   setOperator(String v)        { this.operator = v; }

    public String getDirection()               { return direction; }
    public void   setDirection(String v)       { this.direction = v; }

    public String getJourneyRef()              { return journeyRef; }
    public void   setJourneyRef(String v)      { this.journeyRef = v; }

    public List<RouteStop> getStops()          { return stops; }
    public void setStops(List<RouteStop> v)    { this.stops = v; }

    @Override
    public String toString() {
        return "BusLineConfig{busId='" + busId + "', lineId='" + lineId
                + "', stops=" + stops.size() + "}";
    }
}

