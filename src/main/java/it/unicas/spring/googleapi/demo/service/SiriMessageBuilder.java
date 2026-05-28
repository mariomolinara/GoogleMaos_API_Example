package it.unicas.spring.googleapi.demo.service;

import it.unicas.spring.googleapi.demo.model.RouteStop;
import it.unicas.spring.googleapi.demo.model.VehicleState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds SIRI 2.0 VehicleMonitoring XML messages as required by the
 * CASSITRACK / OmniMove specification.
 *
 * SIRI 2.0 structure (EN 15531):
 *   Siri / ServiceDelivery / VehicleMonitoringDelivery / VehicleActivity / MonitoredVehicleJourney
 *
 * Route metadata (lineId, lineName, operator…) is read directly from VehicleState,
 * which is populated by VehicleStateService using FleetConfiguration.
 * No dependency on RouteConfiguration is needed here.
 *
 * Reference: OMNIMOVE_CASSITRACK_Specification_V1.1
 */
@Service
public class SiriMessageBuilder {

    private static final DateTimeFormatter ISO_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private static final ZoneId ZONE_IT = ZoneId.of("Europe/Rome");

    @Value("${siri.version:2.0}")
    private String siriVersion;

    @Value("${siri.producer.ref:OMNITRACK-CASSITRACK}")
    private String producerRef;

    @Value("${siri.subscriber.ref:CASSITRACK-CENTRAL-SERVER}")
    private String subscriberRef;

    @Value("${siri.subscription.ref:SUB-OMNITRACK-001}")
    private String subscriptionRef;

    /**
     * Builds a complete SIRI 2.0 VehicleMonitoringDelivery XML document
     * for the vehicle described by the given state.
     *
     * @param state current vehicle state (position, ETA, occupancy, route metadata…)
     * @return SIRI 2.0 XML string
     */
    public String buildVehicleMonitoringDelivery(VehicleState state) {
        Instant now       = Instant.now();
        String nowStr     = formatInstant(now);
        String validUntil = formatInstant(now.plusSeconds(120)); // valid for 2 minutes

        // Expected arrival time at the next stop
        Instant etaInstant = now.plusSeconds(state.getEtaToNextStopSeconds());
        String etaStr      = formatInstant(etaInstant);

        RouteStop nextStop  = state.getNextStop();
        String stopRef      = nextStop != null ? nextStop.getId()    : "UNKNOWN";
        String stopName     = nextStop != null ? nextStop.getName()  : "Unknown";
        int    stopOrder    = nextStop != null ? nextStop.getOrder() : 1;

        String arrivalStatus = arrivalStatus(state.getEtaToNextStopSeconds());
        String dataFrameRef  = formatDate(now);

        // Format decimal values with Locale.US (dot as decimal separator) for XML
        String lonFmt   = String.format(Locale.US, "%.6f", state.getLongitude());
        String latFmt   = String.format(Locale.US, "%.6f", state.getLatitude());
        String bearFmt  = String.format(Locale.US, "%.1f", state.getBearing());
        String speedFmt = String.format(Locale.US, "%.1f", state.getSpeedKmh());

        // Read route metadata from the vehicle state (populated from FleetConfiguration)
        String lineId     = state.getLineId()     != null ? state.getLineId()     : "UNKNOWN";
        String direction  = state.getDirection()  != null ? state.getDirection()  : "OUTBOUND";
        String journeyRef = state.getJourneyRef() != null ? state.getJourneyRef() : "VJ-UNKNOWN";
        String lineName   = state.getLineName()   != null ? state.getLineName()   : "Unknown Line";
        String operator   = state.getOperator()   != null ? state.getOperator()   : "COTRAL";

        return """
<?xml version="1.0" encoding="UTF-8"?>
<Siri version="%s" xmlns="http://www.siri.org.uk/siri"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://www.siri.org.uk/siri http://www.siri.org.uk/schema/2.0/xsd/siri.xsd">
  <ServiceDelivery>
    <ResponseTimestamp>%s</ResponseTimestamp>
    <ProducerRef>%s</ProducerRef>
    <ResponseMessageIdentifier>%s</ResponseMessageIdentifier>
    <VehicleMonitoringDelivery version="%s">
      <ResponseTimestamp>%s</ResponseTimestamp>
      <SubscriberRef>%s</SubscriberRef>
      <SubscriptionRef>%s</SubscriptionRef>
      <Status>true</Status>
      <VehicleActivity>
        <RecordedAtTime>%s</RecordedAtTime>
        <ItemIdentifier>%s-%d</ItemIdentifier>
        <ValidUntilTime>%s</ValidUntilTime>
        <MonitoredVehicleJourney>
          <LineRef>%s</LineRef>
          <DirectionRef>%s</DirectionRef>
          <FramedVehicleJourneyRef>
            <DataFrameRef>%s</DataFrameRef>
            <DatedVehicleJourneyRef>%s</DatedVehicleJourneyRef>
          </FramedVehicleJourneyRef>
          <PublishedLineName>%s</PublishedLineName>
          <OperatorRef>%s</OperatorRef>
          <VehicleRef>%s</VehicleRef>
          <VehicleLocation>
            <Longitude>%s</Longitude>
            <Latitude>%s</Latitude>
          </VehicleLocation>
          <Bearing>%s</Bearing>
          <Velocity>%s</Velocity>
          <Occupancy>%s</Occupancy>
          <MonitoredCall>
            <StopPointRef>%s</StopPointRef>
            <Order>%d</Order>
            <StopPointName>%s</StopPointName>
            <ExpectedArrivalTime>%s</ExpectedArrivalTime>
            <ArrivalStatus>%s</ArrivalStatus>
          </MonitoredCall>
          <Extensions>
            <OmnitrackExtensions xmlns="http://omnitrack.unicas.it/cassitrack/extensions/v1">
              <CrowdingLevel>%d</CrowdingLevel>
              <CrowdingDescription>%s</CrowdingDescription>
              <NextStopEtaSeconds>%d</NextStopEtaSeconds>
              <GpsTimestamp>%s</GpsTimestamp>
              <DataSource>ESP32-QUECTEL-L76K-GNSS</DataSource>
              <NetworkOperator>COTRAL</NetworkOperator>
            </OmnitrackExtensions>
          </Extensions>
        </MonitoredVehicleJourney>
      </VehicleActivity>
    </VehicleMonitoringDelivery>
  </ServiceDelivery>
</Siri>
""".formatted(
                siriVersion,
                nowStr,
                producerRef,
                "MSG-" + now.toEpochMilli(),
                siriVersion,
                nowStr,
                subscriberRef,
                subscriptionRef,
                // VehicleActivity
                formatInstant(state.getLastGpsUpdate() != null ? state.getLastGpsUpdate() : now),
                state.getBusId(), now.toEpochMilli(),
                validUntil,
                // MonitoredVehicleJourney — route metadata from VehicleState
                lineId, direction, dataFrameRef, journeyRef,
                lineName, operator,
                state.getBusId(),
                lonFmt, latFmt, bearFmt, speedFmt,
                state.getOccupancySiri() != null ? state.getOccupancySiri() : "seatsAvailable",
                // MonitoredCall
                stopRef, stopOrder, stopName, etaStr, arrivalStatus,
                // Extensions
                state.getCrowding(),
                crowdingDescription(state.getCrowding()),
                state.getEtaToNextStopSeconds(),
                formatInstant(state.getLastGpsUpdate() != null ? state.getLastGpsUpdate() : now)
        );
    }

    /** Builds a SIRI SubscriptionResponse message. */
    public String buildSubscriptionResponse(boolean success) {
        String now = formatInstant(Instant.now());
        return """
<?xml version="1.0" encoding="UTF-8"?>
<Siri version="%s" xmlns="http://www.siri.org.uk/siri">
  <SubscriptionResponse>
    <ResponseTimestamp>%s</ResponseTimestamp>
    <ResponderRef>%s</ResponderRef>
    <ResponseStatus>
      <ResponseTimestamp>%s</ResponseTimestamp>
      <SubscriberRef>%s</SubscriberRef>
      <SubscriptionRef>%s</SubscriptionRef>
      <Status>%s</Status>
    </ResponseStatus>
  </SubscriptionResponse>
</Siri>
""".formatted(siriVersion, now, producerRef, now,
                subscriberRef, subscriptionRef, success ? "true" : "false");
    }

    // ---- Helpers ----

    private String formatInstant(Instant instant) {
        return ZonedDateTime.ofInstant(instant, ZONE_IT).format(ISO_OFFSET);
    }

    private String formatDate(Instant instant) {
        return ZonedDateTime.ofInstant(instant, ZONE_IT)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private String arrivalStatus(int etaSeconds) {
        if (etaSeconds <= 30) return "arriving";
        return "onTime";
    }

    private String crowdingDescription(int level) {
        return switch (level / 3) {
            case 0      -> "LOW";
            case 1, 2  -> "MEDIUM";
            case 3      -> "HIGH";
            default     -> "FULL";
        };
    }
}
