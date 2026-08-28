package com.bettercontent.worldlifecyclemanager;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.List;

public final class WorldCondenserScreen extends AbstractContainerScreen<WorldCondenserMenu> {
    private record GraphNode(String id, String label, int column, int virtualY, int width, boolean free) {}
    private static final int GRAPH_SCROLL_MAX = 360;
    private static final GraphNode[] GRAPH = {
            new GraphNode("schematicannon_start", "Schematicannon Start", 1, 24, 200, false),
            new GraphNode("embark_budget_iv", "Embark Budget IV · 18", 1, 82, 170, false),
            new GraphNode("embark_budget_iii", "Embark Budget III · 15", 1, 126, 170, false),
            new GraphNode("embark_budget_ii", "Embark Budget II · 12", 1, 170, 170, false),
            new GraphNode("embark_budget_i", "Embark Budget I · 9", 1, 214, 170, false),
            new GraphNode("free_embark", "Embark Mode · 6", 1, 266, 180, true),
            new GraphNode("class_wayfinder", "Wayfinder", 0, 322, 120, false),
            new GraphNode("class_field_cook", "Field Cook", 1, 322, 120, false),
            new GraphNode("class_rail_scout", "Rail Scout", 2, 322, 120, false),
            new GraphNode("class_flood_runner", "Flood Runner", 0, 364, 120, false),
            new GraphNode("class_market_runner", "Market Runner", 1, 364, 120, false),
            new GraphNode("class_trail_wrangler", "Trail Wrangler", 2, 364, 120, false),
            new GraphNode("biome_selection", "Biome Selection", 1, 424, 180, false)
    };
    private int seenRevision = -1;
    private int tab;
    private int localIndex;
    private int publishedIndex;
    private int perkScroll = GRAPH_SCROLL_MAX;
    private List<SchematicLocalStore.Entry> localSchematics = List.of();
    private String localSchematicError = "";

    public WorldCondenserScreen(WorldCondenserMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 420;
        imageHeight = 226;
        tab = menu.initialTab();
    }

    @Override protected void init() {
        imageWidth = WorldCondenserLayout.panelWidth(width);
        imageHeight = WorldCondenserLayout.panelHeight(height);
        super.init();
        PrestigeClientState.clear();
        if (tab == 1) refreshLocalSchematics(false);
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
                    .bounds(leftPos + (imageWidth - 100) / 2, topPos + imageHeight - 28, 100, 20).build());
            return;
        }
        int x = leftPos + 10;
        int contentWidth = WorldCondenserLayout.contentWidth(imageWidth);
        int tabWidth = WorldCondenserLayout.tabWidth(contentWidth);
        int tabY = WorldCondenserLayout.tabTop(imageWidth, imageHeight);
        addRenderableWidget(tabButton("Configure", x, tabY, tabWidth, 0));
        addRenderableWidget(tabButton("Schematics", x + tabWidth + 5, tabY, tabWidth, 1));
        addRenderableWidget(tabButton("Perks", x + (tabWidth + 5) * 2, tabY, tabWidth, 2));
        if (tab == 0) rebuildReset(state, x);
        else if (tab == 1) rebuildSchematics(state, x);
        else rebuildPerks(state, x);
    }

    private Button tabButton(String label, int x, int y, int width, int value) {
        return Button.builder(Component.literal((tab == value ? "> " : "") + label), button -> {
            switchTab(value);
        }).bounds(x, topPos + y, width, 20).build();
    }

    private void rebuildReset(PrestigeNetwork.StatePacket state, int x) {
        int width = WorldCondenserLayout.contentWidth(imageWidth);
        int y = topPos + WorldCondenserLayout.contentTop(imageWidth, imageHeight);
        boolean compact = WorldCondenserLayout.compact(imageWidth, imageHeight);
        int rowGap = compact ? 22 : 24;
        for (int slot = 0; slot < 3; slot++) {
            String label = switch (slot) { case 0 -> "Primary"; case 1 -> "Secondary"; default -> "Tertiary"; };
            String selected = slot < state.selectedBiomes().size() ? state.selectedBiomes().get(slot) : "";
            boolean enabled = state.operator() && state.status().equals("draft")
                    && (slot == 0 || state.selectedBiomes().size() >= slot);
            int buttonWidth = slot == 0 ? width : Math.max(1, width - 70);
            int selectedSlot = slot;
            Button biome = Button.builder(Component.literal(label + ": " + (selected.isEmpty() ? "none" : shortText(selected, 34))), button -> {
                int index = selected.isEmpty() ? -1 : state.biomes().indexOf(selected);
                String next = nextUniqueBiome(state, selectedSlot, index);
                PrestigeNetwork.sendAction(biomeAction(selectedSlot), actionPos(), next);
            }).bounds(x, y, buttonWidth, 20).build();
            biome.active = enabled && !state.biomes().isEmpty();
            addRenderableWidget(biome);
            if (slot > 0) {
                Button clear = Button.builder(Component.literal("Clear"), button ->
                        PrestigeNetwork.sendAction(biomeAction(selectedSlot), actionPos(), "clear"))
                        .bounds(x + width - 64, y, 64, 20).build();
                clear.active = enabled && !selected.isEmpty();
                addRenderableWidget(clear);
            }
            y += rowGap;
        }
        if (state.operator()) {
            int actionWidth = Math.max(52, Math.min(88, (width - 12) / 4));
            Button stage = Button.builder(Component.literal("Stage reset"), button ->
                    PrestigeNetwork.sendAction(PrestigeNetwork.Action.STAGE, actionPos(), ""))
                    .bounds(x, y, actionWidth, 20).build();
            stage.active = !menu.remote() && state.status().equals("draft")
                    && !state.selectedBiomes().isEmpty();
            addRenderableWidget(stage);
            Button cancel = Button.builder(Component.literal("Cancel stage"), button ->
                    PrestigeNetwork.sendAction(PrestigeNetwork.Action.CANCEL, actionPos(), ""))
                    .bounds(x + actionWidth + 6, y, actionWidth, 20).build();
            cancel.active = state.status().equals("staged");
            addRenderableWidget(cancel);
            y += rowGap;
            Button commit = Button.builder(Component.literal("COMMIT PERMANENT RESET"), button ->
                    PrestigeNetwork.sendAction(PrestigeNetwork.Action.COMMIT, actionPos(), ""))
                .bounds(x, y, width, 20).build();
            commit.active = !menu.remote() && state.status().equals("staged");
            addRenderableWidget(commit);
        }
        if (!state.operator()) {
            addRenderableWidget(Button.builder(Component.literal("Open schematics"), button -> switchTab(1))
                    .bounds(x, topPos + imageHeight - 28, width, 20).build());
        }
    }

    private static PrestigeNetwork.Action biomeAction(int slot) {
        return switch (slot) {
            case 0 -> PrestigeNetwork.Action.SET_BIOME_1;
            case 1 -> PrestigeNetwork.Action.SET_BIOME_2;
            case 2 -> PrestigeNetwork.Action.SET_BIOME_3;
            default -> throw new IllegalArgumentException("invalid biome slot");
        };
    }

    private static String nextUniqueBiome(PrestigeNetwork.StatePacket state, int slot, int currentIndex) {
        for (int offset = 1; offset <= state.biomes().size(); offset++) {
            String candidate = state.biomes().get(Math.floorMod(currentIndex + offset, state.biomes().size()));
            boolean usedElsewhere = false;
            for (int index = 0; index < state.selectedBiomes().size(); index++) {
                if (index != slot && state.selectedBiomes().get(index).equals(candidate)) { usedElsewhere = true; break; }
            }
            if (!usedElsewhere) return candidate;
        }
        throw new IllegalStateException("no unused biome preference remains");
    }

    private void rebuildPerks(PrestigeNetwork.StatePacket state, int x) {
        int contentWidth = WorldCondenserLayout.contentWidth(imageWidth);
        int graphTop = WorldCondenserLayout.graphTop(imageWidth, imageHeight);
        addRenderableWidget(Button.builder(Component.literal("↑"), button -> scrollPerks(-78))
                .bounds(x + contentWidth - 30, topPos + graphTop, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("↓"), button -> scrollPerks(78))
                .bounds(x + contentWidth - 30, topPos + graphTop + 24, 30, 20).build());
        for (GraphNode node : GRAPH) {
            int y = topPos + graphTop + node.virtualY() - perkScroll;
            if (y < topPos + graphTop - 4 || y + 30 > graphBottom()) continue;
            int bx = graphX(x, node);
            if (node.free()) addFreeNode(state, node, bx, y);
            else addPerkButton(state, node.id(), node.label(), bx, y, graphNodeWidth(node));
        }
    }

    private int graphX(int x, GraphNode node) {
        int contentWidth = WorldCondenserLayout.contentWidth(imageWidth);
        int nodeWidth = graphNodeWidth(node);
        return x + WorldCondenserLayout.graphNodeX(contentWidth, node.column(), nodeWidth);
    }

    private int graphNodeWidth(GraphNode node) {
        return WorldCondenserLayout.graphNodeWidth(WorldCondenserLayout.contentWidth(imageWidth), node.column(), node.width());
    }

    private void addFreeNode(PrestigeNetwork.StatePacket state, GraphNode node, int x, int y) {
        boolean unlocked = hasAllClasses(state);
        Button button = Button.builder(Component.literal((unlocked ? "◆ " : "◇ ") + node.label() + " · FREE"), ignored -> {})
                .bounds(x, y, graphNodeWidth(node), 30).build();
        button.active = false;
        button.setTooltip(Tooltip.create(Component.literal(
                "Replaces class selection with a 6-point Embark screen when all six classes are selected.")));
        addRenderableWidget(button);
    }

    private void addPerkButton(PrestigeNetwork.StatePacket state, String id, String label, int x, int y, int width) {
        boolean selected = state.perks().contains(id);
        Button button = Button.builder(Component.literal((selected ? "✓ " : "") + label), ignored ->
                PrestigeNetwork.sendAction(PrestigeNetwork.Action.TOGGLE_PERK, actionPos(), id))
                .bounds(x, y, width, 30).build();
        button.setTooltip(Tooltip.create(Component.literal(perkDescription(id))));
        button.active = state.operator() && state.status().equals("draft");
        addRenderableWidget(button);
    }

    private static String perkDescription(String id) {
        return switch (id) {
            case "biome_selection" -> "Choose up to three ordered spawn-biome preferences for each successor.";
            case "class_wayfinder" -> "Unlock the Wayfinder starting class after Biome Selection.";
            case "class_field_cook" -> "Unlock the Field Cook starting class after Biome Selection.";
            case "class_rail_scout" -> "Unlock the Rail Scout starting class after Biome Selection.";
            case "class_flood_runner" -> "Unlock the Flood Runner starting class after Biome Selection.";
            case "class_market_runner" -> "Unlock the Market Runner starting class after Biome Selection.";
            case "class_trail_wrangler" -> "Unlock the Trail Wrangler starting class after Biome Selection.";
            case "embark_budget_i" -> "Increase the Embark budget from 6 to 9 points.";
            case "embark_budget_ii" -> "Increase the Embark budget from 9 to 12 points.";
            case "embark_budget_iii" -> "Increase the Embark budget from 12 to 15 points.";
            case "embark_budget_iv" -> "Increase the Embark budget from 15 to 18 points.";
            case "schematicannon_start" -> "After every other paid perk, give each player one Schematicannon when onboarding completes.";
            default -> id;
        };
    }

    private static boolean hasAllClasses(PrestigeNetwork.StatePacket state) {
        return state.perks().contains("biome_selection") && state.perks().containsAll(java.util.List.of("class_wayfinder", "class_field_cook",
                "class_rail_scout", "class_flood_runner", "class_market_runner", "class_trail_wrangler"));
    }

    private void scrollPerks(int amount) {
        perkScroll = Math.max(0, Math.min(GRAPH_SCROLL_MAX, perkScroll + amount));
        rebuild();
    }

    private void rebuildSchematics(PrestigeNetwork.StatePacket state, int x) {
        int contentWidth = WorldCondenserLayout.contentWidth(imageWidth);
        int y = topPos + WorldCondenserLayout.contentTop(imageWidth, imageHeight);
        int actionWidth = Math.min(76, Math.max(58, contentWidth / 5));
        int arrowWidth = 24;
        int gap = 4;
        int selectWidth = Math.max(1, contentWidth - actionWidth - arrowWidth * 2 - gap * 3);
        localIndex = SchematicSelector.clamp(localIndex, localSchematics.size());
        String localLabel;
        if (!localSchematicError.isEmpty()) localLabel = "Local scan failed";
        else if (localSchematics.isEmpty()) localLabel = "No local .nbt schematics";
        else localLabel = (localIndex + 1) + "/" + localSchematics.size() + "  " + localSchematics.get(localIndex).name();
        Button local = Button.builder(Component.literal(shortText(localLabel, 34)), button -> {})
                .bounds(x, y, selectWidth, 20).build();
        local.active = false;
        local.setTooltip(Tooltip.create(Component.literal(localSchematicError.isEmpty()
                ? localSchematics.isEmpty()
                    ? "Save a structure with Create's Schematic and Quill into .minecraft/schematics, then click Refresh local."
                    : localSchematics.get(localIndex).name() + " · " + localSchematics.get(localIndex).size() + " bytes"
                : localSchematicError)));
        addRenderableWidget(local);
        Button localPrevious = Button.builder(Component.literal("<"), button -> {
            localIndex = SchematicSelector.previous(localIndex, localSchematics.size()); rebuild();
        }).bounds(x + selectWidth + gap, y, arrowWidth, 20).build();
        localPrevious.active = SchematicSelector.canCycle(localSchematics.size());
        addRenderableWidget(localPrevious);
        Button localNext = Button.builder(Component.literal(">"), button -> {
            localIndex = SchematicSelector.next(localIndex, localSchematics.size()); rebuild();
        }).bounds(x + selectWidth + gap * 2 + arrowWidth, y, arrowWidth, 20).build();
        localNext.active = SchematicSelector.canCycle(localSchematics.size());
        addRenderableWidget(localNext);
        Button publish = Button.builder(Component.literal("Publish"), button -> publishSelectedLocal())
                .bounds(x + contentWidth - actionWidth, y, actionWidth, 20).build();
        publish.active = state.operator() && !localSchematics.isEmpty();
        publish.setTooltip(Tooltip.create(Component.literal(state.operator()
                ? "Publish the selected local schematic under your player name."
                : "Permission level 4 required.")));
        addRenderableWidget(publish);
        y += 30;
        publishedIndex = SchematicSelector.clamp(publishedIndex, state.published().size());
        String published = state.published().isEmpty() ? "Library empty" : (publishedIndex + 1) + "/" + state.published().size()
                + "  " + state.published().get(publishedIndex).author() + "/" + state.published().get(publishedIndex).name();
        Button library = Button.builder(Component.literal(shortText(published, 34)), button -> {})
                .bounds(x, y, selectWidth, 20).build();
        library.active = false;
        if (state.published().isEmpty()) library.setTooltip(Tooltip.create(Component.literal(
                "No lineage schematics have been published yet.")));
        addRenderableWidget(library);
        Button libraryPrevious = Button.builder(Component.literal("<"), button -> {
            publishedIndex = SchematicSelector.previous(publishedIndex, state.published().size()); rebuild();
        }).bounds(x + selectWidth + gap, y, arrowWidth, 20).build();
        libraryPrevious.active = SchematicSelector.canCycle(state.published().size());
        addRenderableWidget(libraryPrevious);
        Button libraryNext = Button.builder(Component.literal(">"), button -> {
            publishedIndex = SchematicSelector.next(publishedIndex, state.published().size()); rebuild();
        }).bounds(x + selectWidth + gap * 2 + arrowWidth, y, arrowWidth, 20).build();
        libraryNext.active = SchematicSelector.canCycle(state.published().size());
        addRenderableWidget(libraryNext);
        Button download = Button.builder(Component.literal("Download"), button -> PrestigeNetwork.sendAction(
                        PrestigeNetwork.Action.DOWNLOAD, actionPos(), state.published().get(publishedIndex).id()))
                .bounds(x + contentWidth - actionWidth, y, actionWidth, 20).build();
        download.active = !state.published().isEmpty();
        addRenderableWidget(download);
        y += 30;
        addRenderableWidget(Button.builder(Component.literal("Refresh local"), button -> refreshLocalSchematics(true))
                .bounds(x, y, Math.min(110, contentWidth), 20).build());
        if (state.operator() && !state.published().isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("Remove selected entry"), button ->
                    PrestigeNetwork.sendAction(PrestigeNetwork.Action.REMOVE, actionPos(), state.published().get(publishedIndex).id()))
                    .bounds(x + 120, y, Math.min(180, Math.max(1, contentWidth - 120)), 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Back to configure"), button -> switchTab(0))
                .bounds(x, topPos + imageHeight - 28, Math.min(170, contentWidth), 20).build());
    }

    private static String shortText(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private BlockPos actionPos() {
        return menu.pos();
    }

    private void refreshLocalSchematics(boolean redraw) {
        try {
            localSchematics = SchematicLocalStore.list(FMLPaths.GAMEDIR.get());
            localSchematicError = "";
            localIndex = SchematicSelector.clamp(localIndex, localSchematics.size());
        } catch (Exception error) {
            localSchematics = List.of();
            localIndex = 0;
            localSchematicError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
        if (redraw) rebuild();
    }

    private void publishSelectedLocal() {
        if (localSchematics.isEmpty()) return;
        SchematicLocalStore.Entry selected = localSchematics.get(localIndex);
        try {
            byte[] data = SchematicLocalStore.read(FMLPaths.GAMEDIR.get(), selected.name());
            PrestigeNetwork.sendPublish(actionPos(), selected.name(), data);
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("Publishing lineage schematic " + selected.name() + "…"), false);
        } catch (Exception error) {
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("Schematic publication failed: " + error.getMessage()), false);
            refreshLocalSchematics(true);
        }
    }

    private void requestCurrentTab() {
        PrestigeNetwork.sendAction(tab == 0 ? PrestigeNetwork.Action.REFRESH_RESET
                : tab == 1 ? PrestigeNetwork.Action.REFRESH_SCHEMATICS : PrestigeNetwork.Action.REFRESH_PERKS, actionPos(), "");
    }

    private void switchTab(int next) {
        if (tab == next) return;
        tab = next;
        if (tab == 1) refreshLocalSchematics(false);
        PrestigeClientState.clear();
        rebuild();
        requestCurrentTab();
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff17131f);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + 26, 0xff3a2345);
        graphics.fill(leftPos + 4, topPos + 54, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xff211a2a);
        if (tab == 2) drawGraphEdges(graphics);
    }

    private void drawGraphEdges(GuiGraphics graphics) {
        for (String id : java.util.List.of("class_wayfinder", "class_field_cook", "class_rail_scout",
                "class_flood_runner", "class_market_runner", "class_trail_wrangler")) {
            edge(graphics, "biome_selection", id);
            edge(graphics, id, "free_embark");
        }
        edge(graphics, "free_embark", "embark_budget_i");
        edge(graphics, "embark_budget_i", "embark_budget_ii");
        edge(graphics, "embark_budget_ii", "embark_budget_iii");
        edge(graphics, "embark_budget_iii", "embark_budget_iv");
        edge(graphics, "embark_budget_iv", "schematicannon_start");
    }

    private void edge(GuiGraphics graphics, String lowerId, String upperId) {
        GraphNode lower = node(lowerId), upper = node(upperId);
        if (lower == null || upper == null) return;
        int base = leftPos + 10;
        int graphTop = WorldCondenserLayout.graphTop(imageWidth, imageHeight);
        int x1 = graphX(base, lower) + graphNodeWidth(lower) / 2;
        int x2 = graphX(base, upper) + graphNodeWidth(upper) / 2;
        int y1 = topPos + graphTop + lower.virtualY() - perkScroll;
        int y2 = topPos + graphTop + upper.virtualY() - perkScroll + 30;
        if ((y1 < topPos + graphTop - 8 && y2 < topPos + graphTop - 8)
                || (y1 > graphBottom() && y2 > graphBottom())) return;
        int mid = (y1 + y2) / 2;
        int color = 0xff725f82;
        graphics.fill(x1 - 1, Math.min(y1, mid), x1 + 1, Math.max(y1, mid), color);
        graphics.fill(Math.min(x1, x2), mid - 1, Math.max(x1, x2), mid + 1, color);
        graphics.fill(x2 - 1, Math.min(mid, y2), x2 + 1, Math.max(mid, y2), color);
    }

    private static GraphNode node(String id) {
        for (GraphNode node : GRAPH) if (node.id().equals(id)) return node;
        return null;
    }

    private int graphBottom() {
        return topPos + imageHeight - 12;
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
        if (tab == 2) {
            boolean compact = WorldCondenserLayout.compact(imageWidth, imageHeight);
            String summary = compact ? "Build: " + state.perks().size() + "/" + state.perkBudget() + " points · bottom → top"
                    : "Upcoming build: " + state.perks().size() + "/" + state.perkBudget() + " points · progression runs bottom to top";
            graphics.drawString(font, summary, 10, compact ? 56 : 66, 0xffdddddd, false);
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
