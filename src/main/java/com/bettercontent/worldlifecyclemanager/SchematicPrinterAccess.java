package com.bettercontent.worldlifecyclemanager;

import net.minecraft.world.level.block.state.BlockState;

public interface SchematicPrinterAccess {
    BlockState worldLifecycleManager$currentState();
    void worldLifecycleManager$replaceCurrentState(BlockState state);
}
