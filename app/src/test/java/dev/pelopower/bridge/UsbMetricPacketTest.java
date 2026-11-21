package dev.pelopower.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public final class UsbMetricPacketTest {
    @Test
    public void packetCarriesExactGattMeasurementsAndCrc() {
        PowerSample sample = new PowerSample(250, 90, 3_200, 1_000);
        WheelTracker.State wheel = new WheelTracker.State(0x01020304L, 0x1234);
        CadenceTracker.State cadence = new CadenceTracker.State(0x5678, 0x9abc);

        byte[] packet = UsbMetricPacket.encode(sample, wheel, cadence, 0x42);

        assertEquals(32, packet.length);
        assertEquals('P', packet[0]);
        assertEquals('P', packet[1]);
        assertEquals(1, packet[2]);
        assertEquals(0x42, packet[3]);
        assertEquals(25, packet[4]);
        assertArrayEquals(
                CyclingPowerMeasurement.metrics(250, wheel, cadence),
                Arrays.copyOfRange(packet, 5, 19));
        assertArrayEquals(
                CyclingCadenceMeasurement.wheelAndCrankData(wheel, cadence),
                Arrays.copyOfRange(packet, 19, 30));
        int expectedCrc = (packet[30] & 0xff) | ((packet[31] & 0xff) << 8);
        assertEquals(expectedCrc, UsbMetricPacket.crc16Ccitt(packet, 0, 30));
        assertEquals(0x4218, expectedCrc);
    }
}
