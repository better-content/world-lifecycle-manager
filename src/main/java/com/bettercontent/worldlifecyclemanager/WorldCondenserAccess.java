package com.bettercontent.worldlifecyclemanager;

final class WorldCondenserAccess {
    private WorldCondenserAccess() {}

    static boolean canCommit(boolean operator, boolean remote, String status) {
        return operator && !remote && "staged".equals(status);
    }

    static boolean canRecover(boolean operator, boolean remote, PrestigeService.Recovery recovery) {
        return operator && !remote && recovery != PrestigeService.Recovery.NONE;
    }
}
