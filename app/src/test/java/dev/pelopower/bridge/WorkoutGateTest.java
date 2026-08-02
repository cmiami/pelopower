package dev.pelopower.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WorkoutGateTest {
    @Test
    public void requiresThreeMovingSamplesAndStopsAfterThirtySeconds() {
        WorkoutGate gate = new WorkoutGate();
        assertEquals(WorkoutGate.State.WAITING, gate.accept(new PowerSample(100, 1_000)));
        assertEquals(WorkoutGate.State.WAITING, gate.accept(new PowerSample(100, 1_100)));
        assertEquals(WorkoutGate.State.ACTIVE, gate.accept(new PowerSample(100, 1_200)));
        assertEquals(WorkoutGate.State.ACTIVE, gate.accept(new PowerSample(0, 31_199)));
        assertEquals(WorkoutGate.State.WAITING, gate.accept(new PowerSample(0, 31_200)));
    }
}

