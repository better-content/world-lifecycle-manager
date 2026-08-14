package com.bettercontent.worldlifecyclemanager;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class SchematicMintMenu extends AbstractContainerMenu {
    private final Container mint;
    public SchematicMintMenu(int id, Inventory inventory, FriendlyByteBuf data) {
        this(id, inventory, find(inventory, data == null ? BlockPos.ZERO : data.readBlockPos()));
    }
    private static Container find(Inventory inventory, BlockPos pos) {
        return inventory.player.level().getBlockEntity(pos) instanceof SchematicMintBlockEntity mint ? mint : new SimpleContainer(4);
    }
    public SchematicMintMenu(int id, Inventory inventory, Container mint) {
        super(PrestigeRegistry.SCHEMATIC_MINT_MENU.get(), id);
        this.mint = mint;
        checkContainerSize(mint, 4);
        mint.startOpen(inventory.player);
        addSlot(new Slot(mint, 0, 44, 35)); addSlot(new Slot(mint, 1, 80, 35)); addSlot(new Slot(mint, 2, 116, 35));
        addSlot(new Slot(mint, 3, 152, 35) { @Override public boolean mayPlace(ItemStack stack) { return false; } });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 142));
    }
    @Override public boolean stillValid(Player player) { return mint.stillValid(player); }
    @Override public void removed(Player player) { super.removed(player); mint.stopOpen(player); }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index); if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), copy = stack.copy();
        if (index < 4) { if (!moveItemStackTo(stack, 4, slots.size(), true)) return ItemStack.EMPTY; }
        else if (!moveItemStackTo(stack, 0, 3, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack); return copy;
    }
}
