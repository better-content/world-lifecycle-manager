package com.bettercontent.worldlifecyclemanager;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SchematicannonSubstitutionsTest {
    @BeforeAll static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (ExceptionInInitializerError error) {
            boolean expectedForgeHarnessFailure = java.util.stream.Stream.iterate((Throwable) error,
                            java.util.Objects::nonNull, Throwable::getCause)
                    .anyMatch(cause -> cause instanceof NoSuchMethodException
                            && cause.getMessage() != null && cause.getMessage().startsWith("net.minecraftforge.network.NetworkEvent"));
            if (!expectedForgeHarnessFailure) throw error;
        }
    }

    @Test void replacementPreservesCompatibleStateProperties() {
        var source = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.WEST)
                .setValue(BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP)
                .setValue(BlockStateProperties.WATERLOGGED, true);
        var replacement = SchematicannonSubstitutions.replacementState(source, Blocks.COBBLESTONE_STAIRS);
        assertEquals(Blocks.COBBLESTONE_STAIRS, replacement.getBlock());
        assertEquals(net.minecraft.core.Direction.WEST, replacement.getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(net.minecraft.world.level.block.state.properties.Half.TOP, replacement.getValue(BlockStateProperties.HALF));
        assertTrue(replacement.getValue(BlockStateProperties.WATERLOGGED));
    }

    @Test void validationRejectsSelfCyclesAndBlockEntities() {
        ResourceLocation stone = BuiltInRegistries.BLOCK.getKey(Blocks.STONE);
        ResourceLocation dirt = BuiltInRegistries.BLOCK.getKey(Blocks.DIRT);
        ResourceLocation chest = BuiltInRegistries.BLOCK.getKey(Blocks.CHEST);
        assertThrows(IllegalArgumentException.class,
                () -> SchematicannonSubstitutions.validateRule(stone, stone, java.util.Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SchematicannonSubstitutions.validateRule(stone, chest, java.util.Map.of()));
        LinkedHashMap<ResourceLocation, ResourceLocation> existing = new LinkedHashMap<>();
        existing.put(dirt, stone);
        assertThrows(IllegalArgumentException.class,
                () -> SchematicannonSubstitutions.validateRule(stone, dirt, existing));
    }
}
