package dev.pelopower.bridge;

/** Converts the leading fields of the Peloton BikeData parcel into bridge metrics. */
public final class PelotonMetricConverter {
    private static final long MAX_CADENCE_RPM = 300;
    private static final long MAX_POWER_CENTIWATTS = 3_276_700;
    private static final double MPH_TO_KPH = 1.609344;

    private PelotonMetricConverter() {}

    public static PowerSample fromRaw(
            long cadenceRpm,
            long powerCentiwatts,
            long monotonicMillis) {
        if (cadenceRpm < 0 || cadenceRpm > MAX_CADENCE_RPM) {
            throw new IllegalArgumentException("Cadence outside expected range: " + cadenceRpm);
        }
        if (powerCentiwatts < 0 || powerCentiwatts > MAX_POWER_CENTIWATTS) {
            throw new IllegalArgumentException("Power outside expected range: " + powerCentiwatts);
        }

        int watts = (int) Math.round(powerCentiwatts / 100.0);
        int speedCentiKph = (int) Math.round(speedMphFromWatts(watts) * MPH_TO_KPH * 100.0);
        return new PowerSample(watts, (int) cadenceRpm, speedCentiKph, monotonicMillis);
    }

    /** Peloton's display-compatible power curve. Its result is miles per hour. */
    static double speedMphFromWatts(double watts) {
        if (watts < 0.1) return 0.0;
        double root = Math.sqrt(watts);
        double speed;
        if (watts < 26.0) {
            speed = 0.057
                    - (0.172 * root)
                    + (0.759 * root * root)
                    - (0.079 * root * root * root);
        } else {
            speed = -1.635
                    + (2.325 * root)
                    - (0.064 * root * root)
                    + (0.001 * root * root * root);
        }
        return Math.max(0.0, speed);
    }
}
