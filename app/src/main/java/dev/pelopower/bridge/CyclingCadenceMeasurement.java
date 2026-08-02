package dev.pelopower.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class CyclingCadenceMeasurement {
    private CyclingCadenceMeasurement() {}

    /** CSC Measurement with both wheel and crank revolution data. */
    public static byte[] wheelAndCrankData(
            WheelTracker.State wheel,
            CadenceTracker.State cadence) {
        return ByteBuffer.allocate(11)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x03)
                .putInt((int) wheel.cumulativeRevolutions())
                .putShort((short) wheel.lastEventTime1024())
                .putShort((short) cadence.cumulativeRevolutions())
                .putShort((short) cadence.lastEventTime1024())
                .array();
    }
}
