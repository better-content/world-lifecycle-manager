package com.bettercontent.worldlifecyclemanager;

final class WorldCondenserLayout {
    static final int MAX_WIDTH = 420;
    static final int MAX_HEIGHT = 226;
    static final int VIEWPORT_MARGIN = 8;

    static int panelWidth(int viewportWidth) {
        return Math.max(1, Math.min(MAX_WIDTH, viewportWidth - VIEWPORT_MARGIN));
    }

    static int panelHeight(int viewportHeight) {
        return Math.max(1, Math.min(MAX_HEIGHT, viewportHeight - VIEWPORT_MARGIN));
    }

    static int contentWidth(int panelWidth) {
        return Math.max(1, panelWidth - 20);
    }

    static boolean compact(int panelWidth, int panelHeight) {
        return panelWidth < MAX_WIDTH || panelHeight < MAX_HEIGHT;
    }

    static int tabTop(int panelWidth, int panelHeight) {
        return compact(panelWidth, panelHeight) ? 32 : 42;
    }

    static int contentTop(int panelWidth, int panelHeight) {
        return compact(panelWidth, panelHeight) ? 58 : 72;
    }

    static int graphTop(int panelWidth, int panelHeight) {
        return compact(panelWidth, panelHeight) ? 70 : 84;
    }

    static int tabWidth(int contentWidth) {
        return Math.max(1, (contentWidth - 10) / 3);
    }

    static int graphNodeWidth(int contentWidth, int column, int preferredWidth) {
        if (preferredWidth <= 120) return Math.min(preferredWidth, Math.max(1, (contentWidth - 16) / 3));
        return Math.min(preferredWidth, Math.max(1, contentWidth - 40));
    }

    static int graphNodeX(int contentWidth, int column, int nodeWidth) {
        if (column == 0) return 0;
        if (column == 2) return contentWidth - nodeWidth;
        return (contentWidth - nodeWidth) / 2;
    }

    private WorldCondenserLayout() {}
}
