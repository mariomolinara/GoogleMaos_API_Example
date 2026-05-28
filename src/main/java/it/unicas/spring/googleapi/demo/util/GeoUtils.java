package it.unicas.spring.googleapi.demo.util;

/**
 * Utilitia' per calcoli geografici (WGS84).
 */
public class GeoUtils {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private GeoUtils() {}

    /**
     * Distanza in metri tra due coordinate (formula di Haversine).
     */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Bearing in gradi (0=Nord, 90=Est, 180=Sud, 270=Ovest)
     * dal punto (lat1,lon1) verso il punto (lat2,lon2).
     */
    public static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double lat1R = Math.toRadians(lat1);
        double lat2R = Math.toRadians(lat2);
        double dLon  = Math.toRadians(lon2 - lon1);
        double x = Math.sin(dLon) * Math.cos(lat2R);
        double y = Math.cos(lat1R) * Math.sin(lat2R)
                - Math.sin(lat1R) * Math.cos(lat2R) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(x, y));
        return (bearing + 360.0) % 360.0;
    }

    /**
     * Stima ETA in secondi basata su distanza e velocita'.
     * Usata come fallback quando Google Maps API non e' disponibile.
     *
     * @param distanceMeters distanza dalla posizione corrente alla fermata
     * @param speedKmh       velocita' corrente del bus
     * @return ETA in secondi (minimo 30s, massimo 3600s)
     */
    public static int estimateEtaSeconds(double distanceMeters, double speedKmh) {
        if (speedKmh < 1.0) speedKmh = 20.0; // velocita' default se fermo
        double speedMs = speedKmh / 3.6;
        int eta = (int) (distanceMeters / speedMs);
        return Math.max(30, Math.min(eta, 3600));
    }

    /**
     * Interpolazione lineare di una coordinata tra due punti.
     * t=0.0 -> punto A, t=1.0 -> punto B
     */
    public static double interpolate(double a, double b, double t) {
        return a + (b - a) * t;
    }
}

