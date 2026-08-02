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

    @Test
    public void appliesUnsignedSetCumulativeValueAndResetsTimingBaseline() {
        WheelTracker tracker = new WheelTracker();
        tracker.accept(3_600, 1_000);
        tracker.accept(3_600, 2_000);

        tracker.setCumulativeRevolutions(0xfffffff0L);
        WheelTracker.State baseline = tracker.accept(3_600, 20_000);
        assertEquals(0xfffffff0L, baseline.cumulativeRevolutions());

        WheelTracker.State moving = tracker.accept(3_600, 21_000);
        assertEquals(0xfffffff4L, moving.cumulativeRevolutions());
    }
}
