package com.bettercontent.worldlifecyclemanager;

import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SchematicannonSubstitutions {
    public static final int MAX_RULES = 64;
    public static final int MAX_ROWS = 256;

    public record Row(ResourceLocation source, int required, int available, ResourceLocation target,
                      int fallbackAvailable, int fallbackNeeded, int covered, int uncovered) {}

    private SchematicannonSubstitutions() {}

    public static boolean eligible(BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) return false;
        if (!(state.getBlock().asItem() instanceof BlockItem)) return false;
        return simpleRequirement(ItemRequirement.of(state, null)) != null;
    }

    public static ItemRequirement.StackRequirement simpleRequirement(ItemRequirement requirement) {
        if (requirement == null || requirement.isEmpty() || requirement.isInvalid()) return null;
        List<ItemRequirement.StackRequirement> required = requirement.getRequiredItems();
        if (required.size() != 1) return null;
        ItemRequirement.StackRequirement stack = required.get(0);
        if (stack.usage != ItemRequirement.ItemUseType.CONSUME || stack.stack.isEmpty()
                || stack.stack.getCount() != 1 || !(stack.stack.getItem() instanceof BlockItem)) return null;
        return stack;
    }

    public static BlockState replacementState(BlockState source, Block target) {
        BlockState result = target.defaultBlockState();
        for (Property<?> sourceProperty : source.getProperties()) {
            Property<?> targetProperty = result.getBlock().getStateDefinition().getProperty(sourceProperty.getName());
            if (targetProperty == null) continue;
            result = copyProperty(source, result, sourceProperty, targetProperty);
        }
        return result;
    }

    private static <S extends Comparable<S>, T extends Comparable<T>> BlockState copyProperty(
            BlockState source, BlockState target, Property<S> sourceProperty, Property<T> targetProperty) {
        String value = sourceProperty.getName(source.getValue(sourceProperty));
        return targetProperty.getValue(value).map(parsed -> target.setValue(targetProperty, parsed)).orElse(target);
    }

    public static boolean available(SchematicannonBlockEntity cannon, ItemRequirement.StackRequirement requirement) {
        return availableCount(cannon, requirement) >= requirement.stack.getCount();
    }

    public static int availableCount(SchematicannonBlockEntity cannon, ItemRequirement.StackRequirement requirement) {
        cannon.findInventories();
        int found = 0;
        for (var optional : cannon.attachedInventories) {
            IItemHandler inventory = optional.orElse(null);
            if (inventory == null) continue;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                ItemStack extracted = inventory.extractItem(slot, Integer.MAX_VALUE, true);
                if (requirement.matches(extracted)) found += extracted.getCount();
            }
        }
        return found;
    }

    public static int nativeRequirement(SchematicannonBlockEntity cannon, Item item) {
        return cannon.checklist.required.getOrDefault(item, 0);
    }

    public static List<Row> evaluate(SchematicannonBlockEntity cannon,
                                     Map<ResourceLocation, ResourceLocation> substitutions) {
        Map<Item, Integer> required = new LinkedHashMap<>();
        cannon.checklist.required.forEach((item, count) -> required.put(item, count.intValue()));
        Map<Item, Integer> gathered = new LinkedHashMap<>();
        cannon.checklist.gathered.forEach((item, count) -> gathered.put(item, count.intValue()));
        List<Row> rows = new ArrayList<>();
        required.entrySet().stream().sorted(Comparator.comparing(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey()).toString()))
                .limit(MAX_ROWS).forEach(entry -> {
                    if (!(entry.getKey() instanceof BlockItem sourceItem)) return;
                    BlockState sourceState = sourceItem.getBlock().defaultBlockState();
                    if (!eligible(sourceState)) return;
                    ResourceLocation source = BuiltInRegistries.BLOCK.getKey(sourceItem.getBlock());
                    int need = entry.getValue();
                    int have = gathered.getOrDefault(entry.getKey(), 0);
                    int shortage = Math.max(0, need - have);
                    ResourceLocation target = substitutions.get(source);
                    int fallbackAvailable = 0;
                    if (target != null) {
                        Block targetBlock = BuiltInRegistries.BLOCK.get(target);
                        Item targetItem = targetBlock.asItem();
                        int targetNativeNeed = required.getOrDefault(targetItem, 0);
                        fallbackAvailable = Math.max(0, gathered.getOrDefault(targetItem, 0) - targetNativeNeed);
                    }
                    int covered = Math.min(shortage, fallbackAvailable);
                    rows.add(new Row(source, need, have, target, fallbackAvailable, shortage, covered, shortage - covered));
                });
        return List.copyOf(rows);
    }

    public static void validateRule(ResourceLocation sourceId, ResourceLocation targetId,
                                    Map<ResourceLocation, ResourceLocation> existing) {
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
            throw new IllegalArgumentException("substitution source and target must be different registered blocks");
        }
        if (!BuiltInRegistries.BLOCK.containsKey(sourceId) || !BuiltInRegistries.BLOCK.containsKey(targetId)) {
            throw new IllegalArgumentException("substitution contains an unregistered block");
        }
        BlockState source = BuiltInRegistries.BLOCK.get(sourceId).defaultBlockState();
        BlockState target = replacementState(source, BuiltInRegistries.BLOCK.get(targetId));
        if (!eligible(source) || !eligible(target)) throw new IllegalArgumentException("substitution requires ordinary single-item blocks");
        ResourceLocation cursor = targetId;
        for (int depth = 0; depth <= MAX_RULES; depth++) {
            if (cursor.equals(sourceId)) throw new IllegalArgumentException("substitution cycles are not allowed");
            cursor = existing.get(cursor);
            if (cursor == null) return;
        }
        throw new IllegalArgumentException("substitution chain exceeds the rule limit");
    }
}
