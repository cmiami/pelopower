package dev.pelopower.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WheelTrackerTest {
    @Test
    public void accumulatesVirtualWheelRevolutionsFromSpeed() {
        WheelTracker tracker = new WheelTracker();
        tracker.accept(3_600, 1_000); // 36 km/h = 10 m/s.
        WheelTracker.State state = tracker.accept(3_600, 2_000);
        assertEquals(4, state.cumulativeRevolutions());
        assertEquals(1882, state.lastEventTime1024());
    }
}
