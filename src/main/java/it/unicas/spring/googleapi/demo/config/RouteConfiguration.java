package it.unicas.spring.googleapi.demo.config;

import it.unicas.spring.googleapi.demo.model.RouteStop;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configurazione del percorso bus.
 * Le fermate sono definite in application.properties come:
 *   route.stops[0].id=STOP-001
 *   route.stops[0].name=Stazione FS Cassino
 *   route.stops[0].latitude=41.4912
 *   route.stops[0].longitude=13.8306
 *   route.stops[0].order=1
 */
@Component
@ConfigurationProperties(prefix = "route")
public class RouteConfiguration {

    private String lineId = "CASSINO-LINE-1";
    private String lineName = "Linea 1 Cassino";
    private String operator = "COTRAL";
    private String direction = "OUTBOUND";
    private String journeyRef = "VJ-CASSINO-001";
    private List<RouteStop> stops = new ArrayList<>();

    public String getLineId() { return lineId; }
    public void setLineId(String lineId) { this.lineId = lineId; }

    public String getLineName() { return lineName; }
    public void setLineName(String lineName) { this.lineName = lineName; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getJourneyRef() { return journeyRef; }
    public void setJourneyRef(String journeyRef) { this.journeyRef = journeyRef; }

    public List<RouteStop> getStops() { return stops; }
    public void setStops(List<RouteStop> stops) { this.stops = stops; }
}

