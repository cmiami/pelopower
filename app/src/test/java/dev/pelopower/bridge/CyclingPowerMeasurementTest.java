package dev.pelopower.bridge;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class CyclingPowerMeasurementTest {
    @Test
    public void encodesFlagsAndWattsLittleEndian() {
        WheelTracker.State wheel = new WheelTracker.State(0x01020304L, 0x5678);
        CadenceTracker.State cadence = new CadenceTracker.State(42, 0x1234);
        assertArrayEquals(new byte[] {
                        0x30, 0, (byte) 0xFA, 0,
                        0x04, 0x03, 0x02, 0x01, (byte) 0xf0, (byte) 0xac,
                        42, 0, 0x34, 0x12},
                CyclingPowerMeasurement.metrics(250, wheel, cadence));
    }
}
