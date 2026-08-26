package com.bettercontent.worldlifecyclemanager;

import com.simibubi.create.content.schematics.cannon.SchematicannonScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

public final class SchematicannonSubstitutionClient {
    private static final int PANEL_WIDTH = 196;
    private static final int PANEL_GAP = 4;
    private static final int ROW_RIGHT = 137;
    private static final int ACTION_LEFT = 140;
    private static final int ACTION_RIGHT = 191;
    private static SchematicannonSubstitutionNetwork.StatePacket state;
    private static ResourceLocation selected;
    private static int scroll;
    private static int ticks;

    private SchematicannonSubstitutionClient() {}

    static void accept(SchematicannonSubstitutionNetwork.StatePacket packet) {
        state = packet;
        if (selected == null || packet.rows().stream().noneMatch(row -> row.source().equals(selected))) {
            selected = packet.rows().stream().filter(row -> row.target() != null).findFirst()
                    .or(() -> packet.rows().stream().findFirst()).map(SchematicannonSubstitutions.Row::source).orElse(null);
        }
        scroll = Math.min(scroll, Math.max(0, packet.rows().size() - 6));
    }

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SchematicannonScreen screen) || screen.getMenu().contentHolder == null) return;
        state = null; selected = null; scroll = 0;
        SchematicannonSubstitutionNetwork.request(screen.getMenu().contentHolder.getBlockPos());
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(Minecraft.getInstance().screen instanceof SchematicannonScreen screen)
                || screen.getMenu().contentHolder == null || ++ticks % 20 != 0) return;
        SchematicannonSubstitutionNetwork.request(screen.getMenu().contentHolder.getBlockPos());
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof SchematicannonScreen screen) || screen.getMenu().contentHolder == null) return;
        BlockPos pos = screen.getMenu().contentHolder.getBlockPos();
        if (state == null || !state.pos().equals(pos)) return;
        renderPanel(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }

    private static void renderPanel(SchematicannonScreen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        int x = panelX(screen);
        int y = screen.getGuiTop();
        int width = PANEL_WIDTH;
        graphics.fill(x, y, x + width, y + 170, 0xf01c1822);
        graphics.fill(x + 2, y + 2, x + width - 2, y + 21, 0xff4b3658);
        graphics.drawString(Minecraft.getInstance().font, Component.translatable("screen.world_lifecycle_manager.substitutions"), x + 7, y + 8, 0xfff2dcff, false);
        List<SchematicannonSubstitutions.Row> rows = state.rows();
        if (rows.isEmpty()) {
            graphics.drawWordWrap(Minecraft.getInstance().font, Component.translatable("screen.world_lifecycle_manager.substitutions.empty"), x + 8, y + 30, width - 16, 0xffbdb3c5);
        }
        int first = Math.min(scroll, Math.max(0, rows.size() - 6));
        for (int visible = 0; visible < 6 && first + visible < rows.size(); visible++) {
            var row = rows.get(first + visible);
            int rowY = y + 26 + visible * 20;
            int color = row.source().equals(selected) ? 0xff5f4770 : 0xff302838;
            graphics.fill(x + 5, rowY, x + ROW_RIGHT, rowY + 18, color);
            ItemStack icon = new ItemStack(BuiltInRegistries.BLOCK.get(row.source()));
            graphics.renderItem(icon, x + 7, rowY + 1);
            String counts = row.available() + "/" + row.required();
            String name = icon.getHoverName().getString();
            graphics.drawString(Minecraft.getInstance().font, trim(name, 12), x + 26, rowY + 3,
                    row.uncovered() > 0 ? 0xffffb0a8 : 0xffe5dde9, false);
            graphics.drawString(Minecraft.getInstance().font, counts, x + 103, rowY + 3, 0xffc8bdcf, false);
        }
        graphics.drawString(Minecraft.getInstance().font, Component.translatable("screen.world_lifecycle_manager.fallback"), x + ACTION_LEFT, y + 26, 0xffc8bdcf, false);
        graphics.fill(x + 155, y + 40, x + 175, y + 60, 0xff0e0c11);
        var selectedRow = selectedRow();
        if (selectedRow != null && selectedRow.target() != null) {
            graphics.renderItem(new ItemStack(BuiltInRegistries.BLOCK.get(selectedRow.target())), x + 157, y + 42);
        }
        if (selectedRow != null) {
            int summaryColor = selectedRow.uncovered() > 0 ? 0xffff9d94 : 0xffa8e6b1;
            graphics.drawString(Minecraft.getInstance().font, "Need " + selectedRow.fallbackNeeded(), x + ACTION_LEFT, y + 68, summaryColor, false);
            graphics.drawString(Minecraft.getInstance().font, "Ready " + selectedRow.fallbackAvailable(), x + ACTION_LEFT, y + 80, summaryColor, false);
            graphics.drawString(Minecraft.getInstance().font, "Open " + selectedRow.uncovered(), x + ACTION_LEFT, y + 92, summaryColor, false);
        }
        graphics.fill(x + ACTION_LEFT, y + 126, x + ACTION_RIGHT, y + 143, 0xff493b50);
        graphics.drawString(Minecraft.getInstance().font, Component.translatable("screen.world_lifecycle_manager.clear"), x + 151, y + 131, 0xffffffff, false);
        graphics.fill(x + ACTION_LEFT, y + 147, x + ACTION_RIGHT, y + 164, 0xff633946);
        graphics.drawString(Minecraft.getInstance().font, Component.translatable("screen.world_lifecycle_manager.clear_all"), x + 143, y + 152, 0xffffffff, false);
        if (mouseX >= x + 155 && mouseX < x + 175 && mouseY >= y + 40 && mouseY < y + 60) {
            graphics.renderTooltip(Minecraft.getInstance().font,
                    Component.translatable("screen.world_lifecycle_manager.ghost_hint"), mouseX, mouseY);
        }
    }

    @SubscribeEvent
    public static void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0 || !(event.getScreen() instanceof SchematicannonScreen screen)
                || screen.getMenu().contentHolder == null || state == null) return;
        int x = panelX(screen);
        int y = screen.getGuiTop();
        double mouseX = event.getMouseX(), mouseY = event.getMouseY();
        int first = Math.min(scroll, Math.max(0, state.rows().size() - 6));
        if (mouseX >= x + 5 && mouseX < x + ROW_RIGHT && mouseY >= y + 26 && mouseY < y + 146) {
            int index = first + ((int) mouseY - y - 26) / 20;
            if (index >= 0 && index < state.rows().size()) selected = state.rows().get(index).source();
            event.setCanceled(true); return;
        }
        BlockPos pos = screen.getMenu().contentHolder.getBlockPos();
        if (selected != null && mouseX >= x + 155 && mouseX < x + 175 && mouseY >= y + 40 && mouseY < y + 60) {
            ItemStack carried = screen.getMenu().getCarried();
            if (carried.getItem() instanceof BlockItem item) {
                SchematicannonSubstitutionNetwork.edit(SchematicannonSubstitutionNetwork.EditKind.SET, pos, selected,
                        BuiltInRegistries.BLOCK.getKey(item.getBlock()));
            } else {
                SchematicannonSubstitutionNetwork.edit(SchematicannonSubstitutionNetwork.EditKind.CLEAR, pos, selected, null);
            }
            event.setCanceled(true); return;
        }
        if (selected != null && mouseX >= x + ACTION_LEFT && mouseX < x + ACTION_RIGHT && mouseY >= y + 126 && mouseY < y + 143) {
            SchematicannonSubstitutionNetwork.edit(SchematicannonSubstitutionNetwork.EditKind.CLEAR, pos, selected, null);
            event.setCanceled(true); return;
        }
        if (mouseX >= x + ACTION_LEFT && mouseX < x + ACTION_RIGHT && mouseY >= y + 147 && mouseY < y + 164) {
            SchematicannonSubstitutionNetwork.edit(SchematicannonSubstitutionNetwork.EditKind.CLEAR_ALL, pos, null, null);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof SchematicannonScreen screen) || state == null) return;
        int x = panelX(screen), y = screen.getGuiTop();
        if (event.getMouseX() < x || event.getMouseX() >= x + PANEL_WIDTH || event.getMouseY() < y || event.getMouseY() >= y + 170) return;
        scroll = Math.max(0, Math.min(Math.max(0, state.rows().size() - 6), scroll + (event.getScrollDelta() < 0 ? 1 : -1)));
        event.setCanceled(true);
    }

    private static SchematicannonSubstitutions.Row selectedRow() {
        if (state == null || selected == null) return null;
        return state.rows().stream().filter(row -> row.source().equals(selected)).findFirst().orElse(null);
    }

    private static int panelX(SchematicannonScreen screen) {
        return screen.getGuiLeft() - PANEL_WIDTH - PANEL_GAP;
    }

    private static String trim(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(1, maximum - 1)) + "…";
    }
}
