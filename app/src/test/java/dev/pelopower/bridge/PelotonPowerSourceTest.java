package dev.pelopower.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PelotonPowerSourceTest {
    @Test
    public void selectsPollingContractForBikePlusAndCrossTrainerModels() {
        assertTrue(PelotonPowerSource.usesBikePlusContract("g700"));
        assertTrue(PelotonPowerSource.usesBikePlusContract("PLTN-ATR01"));
        assertTrue(PelotonPowerSource.usesBikePlusContract("PLTN-TC1VS"));
    }

    @Test
    public void selectsCallbackContractForOriginalBikeModels() {
        assertFalse(PelotonPowerSource.usesBikePlusContract("PLTN-RB1VO"));
        assertFalse(PelotonPowerSource.usesBikePlusContract("unknown"));
    }
}
