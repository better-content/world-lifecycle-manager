package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldCondenserAccessTest {
    @Test void onlyNearbyPhysicalOperatorCanCommitAStagedReset() {
        assertTrue(WorldCondenserAccess.canCommit(true, false, "staged"));
        assertFalse(WorldCondenserAccess.canCommit(false, false, "staged"));
        assertFalse(WorldCondenserAccess.canCommit(true, true, "staged"));
        assertFalse(WorldCondenserAccess.canCommit(true, false, "draft"));
    }

    @Test void recoveryNeedsARecoverableDiagnosisAndPhysicalOperatorMenu() {
        assertTrue(WorldCondenserAccess.canRecover(true, false, PrestigeService.Recovery.DISCARD_DRAFT));
        assertTrue(WorldCondenserAccess.canRecover(true, false, PrestigeService.Recovery.DISCARD_STAGED));
        assertFalse(WorldCondenserAccess.canRecover(false, false, PrestigeService.Recovery.DISCARD_STAGED));
        assertFalse(WorldCondenserAccess.canRecover(true, true, PrestigeService.Recovery.DISCARD_STAGED));
        assertFalse(WorldCondenserAccess.canRecover(true, false, PrestigeService.Recovery.NONE));
    }
}
