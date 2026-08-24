package com.bettercontent.worldlifecyclemanager;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class WorldCondenserScreen extends AbstractContainerScreen<WorldCondenserMenu> {
    private int seenRevision = -1;
    private int tab;
    private int uploadIndex;
    private int publishedIndex;
    private EditBox confirmation;

    public WorldCondenserScreen(WorldCondenserMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 320;
        imageHeight = 220;
        tab = menu.initialTab();
    }

    @Override protected void init() {
        super.init();
        PrestigeClientState.clear();
        rebuild();
        requestCurrentTab();
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (seenRevision != PrestigeClientState.revision()) rebuild();
    }

    private void rebuild() {
        clearWidgets();
        seenRevision = PrestigeClientState.revision();
        var state = PrestigeClientState.state();
        if (state == null) return;
        if (!state.error().isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("Retry"), button -> requestCurrentTab())
                    .bounds(leftPos + 110, topPos + 170, 100, 20).build());
            return;
        }
        int x = leftPos + 10;
        addRenderableWidget(tabButton("Reset", x, 30, 0));
        addRenderableWidget(tabButton("Schematics", x + 96, 30, 1));
        addRenderableWidget(tabButton("Perks", x + 192, 30, 2));
        if (tab == 0) rebuildReset(state, x);
        else if (tab == 1) rebuildSchematics(state, x);
        else rebuildPerks(state, x);
    }

    private Button tabButton(String label, int x, int y, int value) {
        return Button.builder(Component.literal((tab == value ? "> " : "") + label), button -> {
            switchTab(value);
        }).bounds(x, topPos + y, 90, 20).build();
    }

    private void rebuildReset(PrestigeNetwork.StatePacket state, int x) {
        int width = 300;
        int y = topPos + 60;
        Button biome = Button.builder(Component.literal("Biome: " + shortText(state.selectedBiome(), 36)), button -> {
            int index = Math.max(0, state.biomes().indexOf(state.selectedBiome()));
            String next = state.biomes().get((index + 1) % state.biomes().size());
            PrestigeNetwork.sendAction(PrestigeNetwork.Action.SET_BIOME, actionPos(), next);
        }).bounds(x, y, width, 20).build();
        biome.active = state.operator();
        addRenderableWidget(biome);
        y += 26;
        if (state.operator()) {
            if (state.perks().contains("settled_arrival")) {
                addRenderableWidget(Button.builder(Component.literal("Landing: " + state.landing()), button ->
                        PrestigeNetwork.sendAction(PrestigeNetwork.Action.SET_LANDING, actionPos(),
                                state.landing().equals("village") ? "biome" : "village"))
                        .bounds(x, y, width, 20).build());
                y += 24;
            }
            if (state.perks().contains("fallback_attunement") && state.landing().equals("biome")) {
                String fallback = state.fallback().isEmpty() ? "none" : shortText(state.fallback(), 34);
                addRenderableWidget(Button.builder(Component.literal("Fallback: " + fallback), button -> {
                    int index = state.fallback().isEmpty() ? -1 : state.biomes().indexOf(state.fallback());
                    String next = state.biomes().get((index + 1) % state.biomes().size());
                    if (next.equals(state.selectedBiome())) next = state.biomes().get((index + 2) % state.biomes().size());
                    PrestigeNetwork.sendAction(PrestigeNetwork.Action.SET_FALLBACK, actionPos(), next);
                }).bounds(x, y, 240, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Clear"), button ->
                        PrestigeNetwork.sendAction(PrestigeNetwork.Action.SET_FALLBACK, actionPos(), "clear"))
                        .bounds(x + 246, y, 54, 20).build());
                y += 24;
            }
            Button stage = Button.builder(Component.literal("Stage reset"), button ->
                    PrestigeNetwork.sendAction(PrestigeNetwork.Action.STAGE, actionPos(), ""))
                    .bounds(x, y, 88, 20).build();
            stage.active = !menu.remote();
            addRenderableWidget(stage);
            addRenderableWidget(Button.builder(Component.literal("Cancel stage"), button ->
                    PrestigeNetwork.sendAction(PrestigeNetwork.Action.CANCEL, actionPos(), ""))
                    .bounds(x + 94, y, 88, 20).build());
            confirmation = new EditBox(font, x + 190, y, 110, 20, Component.literal("World name"));
            confirmation.setHint(Component.literal(state.worldName()));
            addRenderableWidget(confirmation);
            y += 24;
            Button commit = Button.builder(Component.literal("COMMIT PERMANENT RESET"), button ->
                    PrestigeNetwork.sendAction(PrestigeNetwork.Action.COMMIT, actionPos(), confirmation.getValue()))
                .bounds(x, y, width, 20).build();
            commit.active = !menu.remote();
            addRenderableWidget(commit);
        }
        if (!state.operator()) {
            addRenderableWidget(Button.builder(Component.literal("Open schematics"), button -> switchTab(1))
                    .bounds(x, topPos + 190, width, 20).build());
        }
    }

    private void rebuildPerks(PrestigeNetwork.StatePacket state, int x) {
        String[][] branches = {
                {"expanded_attunement", "Expanded Attunement", "frontier_attunement", "Frontier Attunement"},
                {"safe_arrival", "Safe Arrival", "settled_arrival", "Settled Arrival"},
                {"fallback_attunement", "Fallback Attunement", "fourth_horizon", "Fourth Horizon"}
        };
        for (int column = 0; column < branches.length; column++) {
            int bx = x + column * 101;
            addPerkButton(state, branches[column][0], branches[column][1], bx, topPos + 72);
            addPerkButton(state, branches[column][2], branches[column][3], bx, topPos + 116);
        }
    }

    private void addPerkButton(PrestigeNetwork.StatePacket state, String id, String label, int x, int y) {
        boolean selected = state.perks().contains(id);
        Button button = Button.builder(Component.literal((selected ? "✓ " : "") + label), ignored ->
                PrestigeNetwork.sendAction(PrestigeNetwork.Action.TOGGLE_PERK, actionPos(), id))
                .bounds(x, y, 96, 32).build();
        button.setTooltip(Tooltip.create(Component.literal(switch (id) {
            case "expanded_attunement" -> "Unlock approved modded temperate spawn biomes.";
            case "frontier_attunement" -> "Unlock demanding vanilla frontier spawn biomes.";
            case "safe_arrival" -> "Require solid ground and clear, fluid-free headroom.";
            case "settled_arrival" -> "Allow landing at the nearest village instead of a biome.";
            case "fallback_attunement" -> "Choose a second biome if the primary cannot be found.";
            case "fourth_horizon" -> "Authorize a fourth successor attempt before rollback.";
            default -> id;
        })));
        button.active = state.operator() && !state.status().equals("staged") && !state.status().equals("committed");
        addRenderableWidget(button);
    }

    private void rebuildSchematics(PrestigeNetwork.StatePacket state, int x) {
        int y = topPos + 62;
        uploadIndex = state.uploads().isEmpty() ? 0 : Math.min(uploadIndex, state.uploads().size() - 1);
        String upload = state.uploads().isEmpty() ? "No server uploads" : state.uploads().get(uploadIndex);
        addRenderableWidget(Button.builder(Component.literal("Server upload: " + shortText(upload, 42)), button -> {
            if (!state.uploads().isEmpty()) { uploadIndex = (uploadIndex + 1) % state.uploads().size(); rebuild(); }
        }).bounds(x, y, 210, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Publish"), button -> {
            if (!state.uploads().isEmpty()) PrestigeNetwork.sendAction(PrestigeNetwork.Action.PUBLISH, actionPos(), state.uploads().get(uploadIndex));
        }).bounds(x + 220, y, 80, 20).build());
        y += 30;
        publishedIndex = state.published().isEmpty() ? 0 : Math.min(publishedIndex, state.published().size() - 1);
        String published = state.published().isEmpty() ? "Library empty" : state.published().get(publishedIndex).author()
                + "/" + state.published().get(publishedIndex).name();
        addRenderableWidget(Button.builder(Component.literal("Library: " + shortText(published, 42)), button -> {
            if (!state.published().isEmpty()) { publishedIndex = (publishedIndex + 1) % state.published().size(); rebuild(); }
        }).bounds(x, y, 210, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Download"), button -> {
            if (!state.published().isEmpty()) PrestigeNetwork.sendAction(PrestigeNetwork.Action.DOWNLOAD, actionPos(),
                    state.published().get(publishedIndex).id());
        }).bounds(x + 220, y, 80, 20).build());
        if (state.operator() && !state.published().isEmpty()) {
            y += 30;
            addRenderableWidget(Button.builder(Component.literal("Remove selected entry"), button ->
                    PrestigeNetwork.sendAction(PrestigeNetwork.Action.REMOVE, actionPos(), state.published().get(publishedIndex).id()))
                    .bounds(x, y, 300, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Back to reset"), button -> switchTab(0))
                .bounds(x, topPos + 192, 145, 20).build());
    }

    private static String shortText(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private BlockPos actionPos() {
        return menu.pos();
    }

    private void requestCurrentTab() {
        PrestigeNetwork.sendAction(tab == 0 ? PrestigeNetwork.Action.REFRESH_RESET
                : tab == 1 ? PrestigeNetwork.Action.REFRESH_SCHEMATICS : PrestigeNetwork.Action.REFRESH_PERKS, actionPos(), "");
    }

    private void switchTab(int next) {
        if (tab == next) return;
        tab = next;
        PrestigeClientState.clear();
        rebuild();
        requestCurrentTab();
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff17131f);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + 26, 0xff3a2345);
        graphics.fill(leftPos + 4, topPos + 54, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xff211a2a);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 10, 10, 0xffe6c6ff, false);
        var state = PrestigeClientState.state();
        if (state == null) {
            graphics.drawString(font, "Loading lineage state…", 10, 25, 0xffaaaaaa, false);
            return;
        }
        if (!state.error().isEmpty()) {
            graphics.drawString(font, "State unavailable", 10, 25, 0xffff7777, false);
            graphics.drawWordWrap(font, Component.literal(state.error()), 10, 62, imageWidth - 20, 0xffffaaaa);
            return;
        }
        graphics.drawString(font, "Generation " + state.generation() + " · Total " + state.total()
                + " · Status " + state.status(), 10, 25, 0xffdddddd, false);
        if (tab == 2) graphics.drawString(font, "Upcoming build: " + state.perks().size() + "/" + state.perkBudget()
                + " points", 10, 151, 0xffdddddd, false);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
