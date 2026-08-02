package dev.pelopower.bridge;

/** Converts decoder-provided speed (raw or derived) into BLE cycling cumulative wheel fields. */
public final class WheelTracker {
    public static final int VIRTUAL_WHEEL_CIRCUMFERENCE_MM = 2096;

    public record State(long cumulativeRevolutions, int lastEventTime1024) {}

    private long lastSampleMillis = -1;
    private double partialRevolution;
    private long cumulativeRevolutions;
    private int lastEventTime1024;

    /** Applies the Bluetooth CSC Set Cumulative Value control-point procedure. */
    public synchronized void setCumulativeRevolutions(long requestedRevolutions) {
        cumulativeRevolutions = requestedRevolutions & 0xffffffffL;
        partialRevolution = 0;
        // Make the next sample establish a fresh timing baseline instead of adding time that
        // elapsed while the control-point request was being processed.
        lastSampleMillis = -1;
    }

    public synchronized State accept(int speedCentiKph, long monotonicMillis) {
        if (lastSampleMillis >= 0 && monotonicMillis >= lastSampleMillis && speedCentiKph > 0) {
            long elapsedMillis = Math.min(5_000L, monotonicMillis - lastSampleMillis);
            double metersPerSecond = (speedCentiKph / 100.0) / 3.6;
            double revolutionsPerMillis = metersPerSecond / 1000.0
                    / (VIRTUAL_WHEEL_CIRCUMFERENCE_MM / 1000.0);
            partialRevolution += revolutionsPerMillis * elapsedMillis;
            long wholeRevolutions = (long) partialRevolution;
            if (wholeRevolutions > 0) {
                partialRevolution -= wholeRevolutions;
                cumulativeRevolutions = (cumulativeRevolutions + wholeRevolutions) & 0xffffffffL;
                long lastEventMillis = monotonicMillis
                        - Math.round(partialRevolution / revolutionsPerMillis);
                lastEventTime1024 = (int) ((lastEventMillis * 1024L / 1000L) & 0xffff);
            }
        }
        lastSampleMillis = monotonicMillis;
        return new State(cumulativeRevolutions, lastEventTime1024);
    }
}
