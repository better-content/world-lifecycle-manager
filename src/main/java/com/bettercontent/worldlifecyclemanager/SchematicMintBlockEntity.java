package com.bettercontent.worldlifecyclemanager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public final class SchematicMintBlockEntity extends BlockEntity implements Container, MenuProvider {
    public static final int INPUTS = 3;
    public static final int OUTPUT = 3;
    private final NonNullList<ItemStack> items = NonNullList.withSize(4, ItemStack.EMPTY);

    public SchematicMintBlockEntity(BlockPos pos, BlockState state) { super(PrestigeRegistry.SCHEMATIC_MINT_BLOCK_ENTITY.get(), pos, state); }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SchematicMintBlockEntity mint) {
        if (level.getGameTime() % 5 != 0 || !mint.items.get(OUTPUT).isEmpty()) return;
        SimpleContainer input = new SimpleContainer(INPUTS);
        for (int i = 0; i < INPUTS; i++) input.setItem(i, mint.items.get(i));
        level.getRecipeManager().getRecipeFor(PrestigeRegistry.SCHEMATIC_MINTING.get(), input, level).ifPresent(recipe -> {
            ItemStack result = recipe.assemble(input, level.registryAccess());
            if (result.isEmpty()) return;
            for (int i = 0; i < INPUTS; i++) if (!mint.items.get(i).isEmpty()) mint.items.get(i).shrink(1);
            mint.items.set(OUTPUT, result);
            mint.setChanged();
        });
    }

    @Override protected void saveAdditional(CompoundTag tag) { super.saveAdditional(tag); ContainerHelper.saveAllItems(tag, items); }
    @Override public void load(CompoundTag tag) { super.load(tag); ContainerHelper.loadAllItems(tag, items); }
    @Override public Component getDisplayName() { return Component.translatable("screen.world_lifecycle_manager.schematic_mint"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new SchematicMintMenu(id, inventory, this); }
    @Override public int getContainerSize() { return items.size(); }
    @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    @Override public ItemStack getItem(int slot) { return items.get(slot); }
    @Override public ItemStack removeItem(int slot, int amount) { ItemStack result = ContainerHelper.removeItem(items, slot, amount); if (!result.isEmpty()) setChanged(); return result; }
    @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    @Override public void setItem(int slot, ItemStack stack) { items.set(slot, stack); if (stack.getCount() > getMaxStackSize()) stack.setCount(getMaxStackSize()); setChanged(); }
    @Override public boolean stillValid(Player player) { return level != null && level.getBlockEntity(worldPosition) == this && player.distanceToSqr(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5) <= 64; }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot < OUTPUT; }
    @Override public void clearContent() { items.clear(); setChanged(); }
}
