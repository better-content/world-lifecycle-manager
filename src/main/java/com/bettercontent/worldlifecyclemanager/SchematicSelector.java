package com.bettercontent.worldlifecyclemanager;

final class SchematicSelector {
    private SchematicSelector() {}

    static int clamp(int index, int size) {
        return size <= 0 ? 0 : Math.max(0, Math.min(index, size - 1));
    }

    static int previous(int index, int size) {
        return size <= 1 ? clamp(index, size) : Math.floorMod(index - 1, size);
    }

    static int next(int index, int size) {
        return size <= 1 ? clamp(index, size) : (index + 1) % size;
    }

    static boolean canCycle(int size) {
        return size > 1;
    }
}
