package com.bettercontent.worldlifecyclemanager;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class SchematicMintScreen extends AbstractContainerScreen<SchematicMintMenu> {
    public SchematicMintScreen(SchematicMintMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageHeight = 166; }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff2b2924);
        graphics.fill(leftPos + 7, topPos + 18, leftPos + imageWidth - 7, topPos + 70, 0xff514b3d);
        for (int x : new int[]{44, 80, 116, 152}) graphics.fill(leftPos + x - 1, topPos + 34, leftPos + x + 17, topPos + 52, 0xff181713);
        graphics.drawString(font, "+", leftPos + 65, topPos + 39, 0xffd8c783, false);
        graphics.drawString(font, "+", leftPos + 101, topPos + 39, 0xffd8c783, false);
        graphics.drawString(font, "→", leftPos + 136, topPos + 39, 0xffd8c783, false);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0xffefe4bd, false);
        graphics.drawString(font, playerInventoryTitle, 8, 72, 0xffefe4bd, false);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) { renderBackground(graphics); super.render(graphics, mouseX, mouseY, partialTick); renderTooltip(graphics, mouseX, mouseY); }
}
