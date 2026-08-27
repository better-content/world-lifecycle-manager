package com.bettercontent.worldlifecyclemanager.mixin;

import com.bettercontent.worldlifecyclemanager.SchematicPrinterAccess;
import com.simibubi.create.content.schematics.SchematicPrinter;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = SchematicPrinter.class, remap = false)
public abstract class SchematicPrinterMixin implements SchematicPrinterAccess {
    @Shadow private SchematicLevel blockReader;
    @Shadow public abstract BlockPos getCurrentTarget();

    @Override
    public BlockState worldLifecycleManager$currentState() {
        return blockReader.getBlockState(getCurrentTarget());
    }

    @Override
    public void worldLifecycleManager$replaceCurrentState(BlockState state) {
        blockReader.setBlock(getCurrentTarget(), state, 3);
    }
}
