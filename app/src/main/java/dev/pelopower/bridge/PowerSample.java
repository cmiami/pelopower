package dev.pelopower.bridge;

public record PowerSample(int watts, int cadenceRpm, int speedCentiKph, long monotonicMillis) {
    public PowerSample {
        watts = Math.max(0, Math.min(32767, watts));
        cadenceRpm = Math.max(0, Math.min(300, cadenceRpm));
        speedCentiKph = Math.max(0, Math.min(20_000, speedCentiKph));
    }

    public PowerSample(int watts, long monotonicMillis) {
        this(watts, 0, 0, monotonicMillis);
    }

    public PowerSample(int watts, int cadenceRpm, long monotonicMillis) {
        this(watts, cadenceRpm, 0, monotonicMillis);
    }
}
