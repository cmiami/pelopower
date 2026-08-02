package dev.pelopower.bridge;

/**
 * Provisional workout detection independent of Peloton UI internals.
 * A decoder can later provide an explicit workout state without changing the BLE server.
 */
public final class WorkoutGate {
    public enum State { WAITING, ACTIVE }

    private static final int START_WATTS = 5;
    private static final int START_SAMPLES = 3;
    private static final long STOP_AFTER_MILLIS = 30_000L;

    private State state = State.WAITING;
    private int movingSamples;
    private long lastMovingAt;

    public State accept(PowerSample sample) {
        if (sample.watts() >= START_WATTS
                || sample.cadenceRpm() >= 10
                || sample.speedCentiKph() >= 100) {
            movingSamples++;
            lastMovingAt = sample.monotonicMillis();
            if (movingSamples >= START_SAMPLES) {
                state = State.ACTIVE;
            }
        } else {
            movingSamples = 0;
            if (state == State.ACTIVE
                    && sample.monotonicMillis() - lastMovingAt >= STOP_AFTER_MILLIS) {
                state = State.WAITING;
            }
        }
        return state;
    }

    public State state() {
        return state;
    }
}
