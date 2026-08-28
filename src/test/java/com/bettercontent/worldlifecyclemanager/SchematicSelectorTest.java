package com.bettercontent.worldlifecyclemanager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SchematicSelectorTest {
    @Test
    void emptyAndSingletonSelectionsNeverProduceInvalidIndices() {
        assertEquals(0, SchematicSelector.clamp(9, 0));
        assertEquals(0, SchematicSelector.previous(0, 0));
        assertEquals(0, SchematicSelector.next(0, 0));
        assertFalse(SchematicSelector.canCycle(0));

        assertEquals(0, SchematicSelector.clamp(9, 1));
        assertEquals(0, SchematicSelector.previous(0, 1));
        assertEquals(0, SchematicSelector.next(0, 1));
        assertFalse(SchematicSelector.canCycle(1));
    }

    @Test
    void multiEntrySelectionsCycleInBothDirections() {
        assertEquals(2, SchematicSelector.previous(0, 3));
        assertEquals(0, SchematicSelector.next(2, 3));
        assertTrue(SchematicSelector.canCycle(3));
    }
}
