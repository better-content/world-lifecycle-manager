package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WorldCondenserLayoutTest {
    @Test void panelFitsA1280By720ViewportAtGuiScaleFour() {
        int width = WorldCondenserLayout.panelWidth(320);
        int height = WorldCondenserLayout.panelHeight(180);
        assertEquals(312, width);
        assertEquals(172, height);
        assertEquals(4, (320 - width) / 2);
        assertEquals(4, (180 - height) / 2);
        assertTrue(WorldCondenserLayout.compact(width, height));
    }

    @Test void normalViewportPreservesTheOriginalPanelSize() {
        assertEquals(420, WorldCondenserLayout.panelWidth(640));
        assertEquals(226, WorldCondenserLayout.panelHeight(360));
        assertFalse(WorldCondenserLayout.compact(420, 226));
    }

    @Test void tabsAndGraphColumnsStayInsideCompactContent() {
        int content = WorldCondenserLayout.contentWidth(312);
        int tab = WorldCondenserLayout.tabWidth(content);
        assertTrue(tab * 3 + 10 <= content);
        int previousRight = -1;
        for (int column = 0; column < 3; column++) {
            int nodeWidth = WorldCondenserLayout.graphNodeWidth(content, column, 120);
            int nodeX = WorldCondenserLayout.graphNodeX(content, column, nodeWidth);
            assertTrue(nodeX >= 0);
            assertTrue(nodeX + nodeWidth <= content);
            assertTrue(nodeX > previousRight);
            previousRight = nodeX + nodeWidth - 1;
        }
    }
}
