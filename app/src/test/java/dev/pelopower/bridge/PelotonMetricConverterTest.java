package dev.pelopower.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class PelotonMetricConverterTest {
    @Test
    public void convertsCentiwattsCadenceAndDisplaySpeed() {
        PowerSample sample = PelotonMetricConverter.fromRaw(90, 10_000, 1_234);

        assertEquals(100, sample.watts());
        assertEquals(90, sample.cadenceRpm());
        assertEquals(2_610, sample.speedCentiKph());
        assertEquals(1_234, sample.monotonicMillis());
    }

    @Test
    public void zeroPowerProducesZeroSpeed() {
        PowerSample sample = PelotonMetricConverter.fromRaw(0, 0, 1);
        assertEquals(0, sample.speedCentiKph());
    }

    @Test
    public void rejectsUnexpectedParcelValues() {
        assertThrows(IllegalArgumentException.class,
                () -> PelotonMetricConverter.fromRaw(301, 10_000, 1));
        assertThrows(IllegalArgumentException.class,
                () -> PelotonMetricConverter.fromRaw(90, 3_276_701, 1));
    }
}
