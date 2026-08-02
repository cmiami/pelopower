package dev.pelopower.bridge;

/** Converts instantaneous RPM samples into the cumulative crank fields used by BLE cycling profiles. */
public final class CadenceTracker {
    public record State(int cumulativeRevolutions, int lastEventTime1024) {}

    private long lastSampleMillis = -1;
    private double partialRevolution;
    private int cumulativeRevolutions;
    private int lastEventTime1024;

    public synchronized State accept(int cadenceRpm, long monotonicMillis) {
        if (lastSampleMillis >= 0 && monotonicMillis >= lastSampleMillis && cadenceRpm > 0) {
            long elapsedMillis = Math.min(5_000L, monotonicMillis - lastSampleMillis);
            double revolutionsPerMillis = cadenceRpm / 60_000.0;
            partialRevolution += revolutionsPerMillis * elapsedMillis;
            int wholeRevolutions = (int) partialRevolution;
            if (wholeRevolutions > 0) {
                partialRevolution -= wholeRevolutions;
                cumulativeRevolutions = (cumulativeRevolutions + wholeRevolutions) & 0xffff;
                long lastEventMillis = monotonicMillis
                        - Math.round(partialRevolution / revolutionsPerMillis);
                lastEventTime1024 = (int) ((lastEventMillis * 1024L / 1000L) & 0xffff);
            }
        }
        lastSampleMillis = monotonicMillis;
        return new State(cumulativeRevolutions, lastEventTime1024);
    }
}
