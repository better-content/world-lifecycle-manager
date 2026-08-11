package com.bettercontent.prestige;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class WorldCondenserFormation {
    private WorldCondenserFormation() {}

    public static boolean isFormed(Level level, BlockPos interfacePos, BlockState interfaceState) {
        if (!interfaceState.is(PrestigeRegistry.WORLD_CONDENSER_INTERFACE.get())) return false;
        Direction preferred = interfaceState.getValue(WorldCondenserInterfaceBlock.FACING);
        if (matches(level, interfacePos, preferred)) return true;
        for (Direction outward : Direction.values()) {
            if (outward != preferred && matches(level, interfacePos, outward)) return true;
        }
        return false;
    }

    private static boolean matches(Level level, BlockPos interfacePos, Direction outward) {
        BlockPos center = interfacePos.relative(outward.getOpposite());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos cursor = center.offset(dx, dy, dz);
                    if (dx == 0 && dy == 0 && dz == 0) {
                        if (!level.getBlockState(cursor).isAir()) return false;
                    } else if (cursor.equals(interfacePos)) {
                        if (!level.getBlockState(cursor).is(PrestigeRegistry.WORLD_CONDENSER_INTERFACE.get())) return false;
                    } else if (!level.getBlockState(cursor).is(PrestigeRegistry.WORLD_CONDENSER_HULL.get())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
