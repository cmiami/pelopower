package dev.pelopower.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CadenceTrackerTest {
    @Test
    public void accumulatesCrankRevolutionsFromRpm() {
        CadenceTracker tracker = new CadenceTracker();
        tracker.accept(60, 1_000);
        CadenceTracker.State state = tracker.accept(60, 2_000);
        assertEquals(1, state.cumulativeRevolutions());
        assertEquals(2048, state.lastEventTime1024());
    }
}
