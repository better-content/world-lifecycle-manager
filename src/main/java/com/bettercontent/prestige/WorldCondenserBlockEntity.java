package com.bettercontent.prestige;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class WorldCondenserBlockEntity extends BlockEntity implements MenuProvider {
    private boolean attuned;

    public WorldCondenserBlockEntity(BlockPos pos, BlockState state) {
        super(PrestigeRegistry.WORLD_CONDENSER_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean isAttuned() { return attuned; }

    public void attune() {
        if (!attuned) {
            attuned = true;
            setChanged();
        }
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("Attuned", attuned);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        attuned = tag.getBoolean("Attuned");
    }

    @Override public Component getDisplayName() {
        return Component.translatable("screen.prestige.world_condenser");
    }

    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new WorldCondenserMenu(id, inventory, worldPosition);
    }
}
