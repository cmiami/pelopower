package dev.pelopower.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CyclingPowerMeasurement {
    private CyclingPowerMeasurement() {}

    /** BLE Cycling Power Measurement with wheel and crank data for receiver-derived speed/cadence. */
    public static byte[] metrics(
            int watts,
            WheelTracker.State wheel,
            CadenceTracker.State cadence) {
        short bounded = (short) Math.max(0, Math.min(32767, watts));
        return ByteBuffer.allocate(14)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 0x0030)
                .putShort(bounded)
                .putInt((int) wheel.cumulativeRevolutions())
                .putShort((short) (wheel.lastEventTime1024() * 2))
                .putShort((short) cadence.cumulativeRevolutions())
                .putShort((short) cadence.lastEventTime1024())
                .array();
    }
}
