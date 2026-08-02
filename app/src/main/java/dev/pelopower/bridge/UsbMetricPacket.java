package dev.pelopower.bridge;

/** Wire format shared with the nRF52840 USB BLE coprocessor firmware. */
public final class UsbMetricPacket {
    public static final int PACKET_SIZE = 32;
    public static final int PAYLOAD_SIZE = 25;
    public static final int PROTOCOL_VERSION = 1;

    private UsbMetricPacket() {}

    public static byte[] encode(
            PowerSample sample,
            WheelTracker.State wheel,
            CadenceTracker.State cadence,
            int sequence) {
        byte[] power = CyclingPowerMeasurement.metrics(sample.watts(), wheel, cadence);
        byte[] csc = CyclingCadenceMeasurement.wheelAndCrankData(wheel, cadence);
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = 'P';
        packet[1] = 'P';
        packet[2] = PROTOCOL_VERSION;
        packet[3] = (byte) sequence;
        packet[4] = PAYLOAD_SIZE;
        System.arraycopy(power, 0, packet, 5, power.length);
        System.arraycopy(csc, 0, packet, 5 + power.length, csc.length);
        int crc = crc16Ccitt(packet, 0, PACKET_SIZE - 2);
        packet[PACKET_SIZE - 2] = (byte) crc;
        packet[PACKET_SIZE - 1] = (byte) (crc >>> 8);
        return packet;
    }

    static int crc16Ccitt(byte[] value, int offset, int length) {
        int crc = 0xffff;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (value[i] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xffff;
            }
        }
        return crc;
    }
}
