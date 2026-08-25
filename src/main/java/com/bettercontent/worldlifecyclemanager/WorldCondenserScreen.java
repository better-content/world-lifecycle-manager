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
    private record GraphNode(String id, String label, int column, int virtualY, int width, boolean free) {}
    private static final int GRAPH_TOP = 84;
    private static final int GRAPH_SCROLL_MAX = 420;
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
            new GraphNode("free_class_selector", "Class Selector", 1, 416, 180, true),
            new GraphNode("frontier_attunement", "Frontier Attunement", 0, 470, 120, false),
            new GraphNode("settled_arrival", "Settled Arrival", 1, 470, 120, false),
            new GraphNode("fourth_horizon", "Fourth Horizon", 2, 470, 120, false),
            new GraphNode("expanded_attunement", "Expanded Attunement", 0, 516, 120, false),
            new GraphNode("safe_arrival", "Safe Arrival", 1, 516, 120, false),
            new GraphNode("fallback_attunement", "Fallback Attunement", 2, 516, 120, false)
    };
    private int seenRevision = -1;
    private int tab;
    private int uploadIndex;
    private int publishedIndex;
    private int perkScroll = GRAPH_SCROLL_MAX;
    private EditBox confirmation;

    public WorldCondenserScreen(WorldCondenserMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 420;
        imageHeight = 226;
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
        addRenderableWidget(tabButton("Configure", x, 42, 0));
        addRenderableWidget(tabButton("Schematics", x + 130, 42, 1));
        addRenderableWidget(tabButton("Perks", x + 260, 42, 2));
        if (tab == 0) rebuildReset(state, x);
        else if (tab == 1) rebuildSchematics(state, x);
        else rebuildPerks(state, x);
    }

    private Button tabButton(String label, int x, int y, int value) {
        return Button.builder(Component.literal((tab == value ? "> " : "") + label), button -> {
            switchTab(value);
        }).bounds(x, topPos + y, 120, 20).build();
    }

    private void rebuildReset(PrestigeNetwork.StatePacket state, int x) {
        int width = 400;
        int y = topPos + 72;
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
                        .bounds(x + 336, y, 64, 20).build());
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
            confirmation = new EditBox(font, x + 240, y, 160, 20, Component.literal("World name"));
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
        addRenderableWidget(Button.builder(Component.literal("↑"), button -> scrollPerks(-78))
                .bounds(x + 370, topPos + GRAPH_TOP, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("↓"), button -> scrollPerks(78))
                .bounds(x + 370, topPos + GRAPH_TOP + 24, 30, 20).build());
        for (GraphNode node : GRAPH) {
            int y = topPos + GRAPH_TOP + node.virtualY() - perkScroll;
            if (y < topPos + GRAPH_TOP - 4 || y + 30 > graphBottom()) continue;
            int bx = graphX(x, node);
            if (node.free()) addFreeNode(state, node, bx, y);
            else addPerkButton(state, node.id(), node.label(), bx, y, node.width());
        }
    }

    private int graphX(int x, GraphNode node) {
        if (node.column() == 0) return x;
        if (node.column() == 2) return x + 250;
        return x + (400 - node.width()) / 2;
    }

    private void addFreeNode(PrestigeNetwork.StatePacket state, GraphNode node, int x, int y) {
        boolean unlocked = node.id().equals("free_class_selector") ? hasOriginalSix(state) : hasAllClasses(state);
        Button button = Button.builder(Component.literal((unlocked ? "◆ " : "◇ ") + node.label() + " · FREE"), ignored -> {})
                .bounds(x, y, node.width(), 30).build();
        button.active = false;
        button.setTooltip(Tooltip.create(Component.literal(node.id().equals("free_class_selector")
                ? "Activates automatically when all six world-shaping perks are selected."
                : "Replaces class selection with a 6-point Embark screen when all six classes are selected.")));
        addRenderableWidget(button);
    }

    private void addPerkButton(PrestigeNetwork.StatePacket state, String id, String label, int x, int y, int width) {
        boolean selected = state.perks().contains(id);
        Button button = Button.builder(Component.literal((selected ? "✓ " : "") + label), ignored ->
                PrestigeNetwork.sendAction(PrestigeNetwork.Action.TOGGLE_PERK, actionPos(), id))
                .bounds(x, y, width, 30).build();
        button.setTooltip(Tooltip.create(Component.literal(perkDescription(id))));
        button.active = state.operator() && !state.status().equals("staged") && !state.status().equals("committed");
        addRenderableWidget(button);
    }

    private static String perkDescription(String id) {
        return switch (id) {
            case "expanded_attunement" -> "Unlock approved modded temperate spawn biomes.";
            case "frontier_attunement" -> "Unlock demanding vanilla frontier spawn biomes.";
            case "safe_arrival" -> "Require solid ground and clear, fluid-free headroom.";
            case "settled_arrival" -> "Allow landing at the nearest village instead of a biome.";
            case "fallback_attunement" -> "Choose a second biome if the primary cannot be found.";
            case "fourth_horizon" -> "Authorize a fourth successor attempt before rollback.";
            case "class_wayfinder" -> "Unlock the Wayfinder starting class after all six world-shaping perks.";
            case "class_field_cook" -> "Unlock the Field Cook starting class after all six world-shaping perks.";
            case "class_rail_scout" -> "Unlock the Rail Scout starting class after all six world-shaping perks.";
            case "class_flood_runner" -> "Unlock the Flood Runner starting class after all six world-shaping perks.";
            case "class_market_runner" -> "Unlock the Market Runner starting class after all six world-shaping perks.";
            case "class_trail_wrangler" -> "Unlock the Trail Wrangler starting class after all six world-shaping perks.";
            case "embark_budget_i" -> "Increase the Embark budget from 6 to 9 points.";
            case "embark_budget_ii" -> "Increase the Embark budget from 9 to 12 points.";
            case "embark_budget_iii" -> "Increase the Embark budget from 12 to 15 points.";
            case "embark_budget_iv" -> "Increase the Embark budget from 15 to 18 points.";
            case "schematicannon_start" -> "After every other paid perk, give each player one Schematicannon when onboarding completes.";
            default -> id;
        };
    }

    private static boolean hasOriginalSix(PrestigeNetwork.StatePacket state) {
        return state.perks().containsAll(java.util.List.of("expanded_attunement", "frontier_attunement", "safe_arrival",
                "settled_arrival", "fallback_attunement", "fourth_horizon"));
    }

    private static boolean hasAllClasses(PrestigeNetwork.StatePacket state) {
        return hasOriginalSix(state) && state.perks().containsAll(java.util.List.of("class_wayfinder", "class_field_cook",
                "class_rail_scout", "class_flood_runner", "class_market_runner", "class_trail_wrangler"));
    }

    private void scrollPerks(int amount) {
        perkScroll = Math.max(0, Math.min(GRAPH_SCROLL_MAX, perkScroll + amount));
        rebuild();
    }

    private void rebuildSchematics(PrestigeNetwork.StatePacket state, int x) {
        int y = topPos + 72;
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
        addRenderableWidget(Button.builder(Component.literal("Back to configure"), button -> switchTab(0))
                .bounds(x, topPos + imageHeight - 28, 170, 20).build());
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
        if (tab == 2) drawGraphEdges(graphics);
    }

    private void drawGraphEdges(GuiGraphics graphics) {
        edge(graphics, "expanded_attunement", "frontier_attunement");
        edge(graphics, "safe_arrival", "settled_arrival");
        edge(graphics, "fallback_attunement", "fourth_horizon");
        for (String id : java.util.List.of("frontier_attunement", "settled_arrival", "fourth_horizon")) edge(graphics, id, "free_class_selector");
        for (String id : java.util.List.of("class_wayfinder", "class_field_cook", "class_rail_scout",
                "class_flood_runner", "class_market_runner", "class_trail_wrangler")) {
            edge(graphics, "free_class_selector", id);
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
        int x1 = graphX(base, lower) + lower.width() / 2;
        int x2 = graphX(base, upper) + upper.width() / 2;
        int y1 = topPos + GRAPH_TOP + lower.virtualY() - perkScroll;
        int y2 = topPos + GRAPH_TOP + upper.virtualY() - perkScroll + 30;
        if ((y1 < topPos + GRAPH_TOP - 8 && y2 < topPos + GRAPH_TOP - 8)
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
        if (tab == 2) graphics.drawString(font, "Upcoming build: " + state.perks().size() + "/" + state.perkBudget()
                + " points · progression runs bottom to top", 10, 66, 0xffdddddd, false);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
