package it.unicas.spring.googleapi.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Loads the entire bus fleet from application.properties.
 *
 * Each entry under fleet.buses[N] configures one bus line:
 *   fleet.buses[0].bus-id=BUS-001
 *   fleet.buses[0].line-id=CASSINO-LINE-1
 *   fleet.buses[0].stops[0].id=STOP-001
 *   ...
 *
 * Replaces the old single-line RouteConfiguration with a multi-bus fleet model.
 */
@Component
@ConfigurationProperties(prefix = "fleet")
public class FleetConfiguration {

    private List<BusLineConfig> buses = new ArrayList<>();

    public List<BusLineConfig> getBuses()           { return buses; }
    public void setBuses(List<BusLineConfig> buses) { this.buses = buses; }

    /**
     * Returns the configuration for the given busId, or empty if the bus is unknown.
     * Used by VehicleStateService and SiriMessageBuilder to resolve route metadata.
     */
    public Optional<BusLineConfig> findByBusId(String busId) {
        return buses.stream()
                .filter(b -> busId != null && busId.equals(b.getBusId()))
                .findFirst();
    }
}

