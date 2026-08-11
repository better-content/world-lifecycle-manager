package com.bettercontent.prestige;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.bettercontent.prestige.PrestigeMod;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import wayoftime.bloodmagic.core.data.Binding;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

@PrefixGameTestTemplate(false)
public final class WorldCondenserGameTests {
    private WorldCondenserGameTests() {}

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void formationAndAttunementAreValidated(final GameTestHelper helper) {
        BlockPos center = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos interfacePos = center.relative(Direction.NORTH);
        BlockState interfaceState = PrestigeRegistry.WORLD_CONDENSER_INTERFACE.get().defaultBlockState()
                .setValue(WorldCondenserInterfaceBlock.FACING, Direction.NORTH);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos cursor = center.offset(dx, dy, dz);
                    if (cursor.equals(center)) helper.getLevel().setBlockAndUpdate(cursor, Blocks.AIR.defaultBlockState());
                    else if (cursor.equals(interfacePos)) helper.getLevel().setBlockAndUpdate(cursor, interfaceState);
                    else helper.getLevel().setBlockAndUpdate(cursor, PrestigeRegistry.WORLD_CONDENSER_HULL.get().defaultBlockState());
                }
            }
        }
        if (!WorldCondenserFormation.isFormed(helper.getLevel(), interfacePos, interfaceState)) {
            helper.fail("Expected a hollow 3x3x3 shell with one face interface to form");
            return;
        }
        interfaceState = interfaceState.setValue(WorldCondenserInterfaceBlock.FACING, Direction.EAST);
        helper.getLevel().setBlockAndUpdate(interfacePos, interfaceState);
        if (!WorldCondenserFormation.isFormed(helper.getLevel(), interfacePos, interfaceState)) {
            helper.fail("A valid shell depended on the interface block's placement orientation");
            return;
        }
        if (!(helper.getLevel().getBlockEntity(interfacePos) instanceof WorldCondenserBlockEntity entity)) {
            helper.fail("World Condenser interface did not create its block entity");
            return;
        }
        entity.attune();
        CompoundTag saved = entity.saveWithFullMetadata();
        WorldCondenserBlockEntity restored = new WorldCondenserBlockEntity(interfacePos, interfaceState);
        restored.load(saved);
        if (!restored.isAttuned()) {
            helper.fail("World Condenser attunement did not survive NBT persistence");
            return;
        }
        helper.getLevel().setBlockAndUpdate(center.above().east(), Blocks.AIR.defaultBlockState());
        if (WorldCondenserFormation.isFormed(helper.getLevel(), interfacePos, interfaceState)) {
            helper.fail("World Condenser stayed formed after a hull block was removed");
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void attunementRequiresAnyBoundApprenticeOrb(final GameTestHelper helper) {
        var apprentice = ForgeRegistries.ITEMS.getValue(new ResourceLocation("bloodmagic:apprenticebloodorb"));
        if (apprentice == null) {
            helper.fail("Blood Magic Apprentice Blood Orb is not registered");
            return;
        }
        ItemStack unbound = new ItemStack(apprentice);
        if (WorldCondenserInterfaceBlock.isBoundApprenticeOrb(unbound)) {
            helper.fail("An unbound Apprentice Blood Orb satisfied condenser attunement");
            return;
        }
        ItemStack bound = unbound.copy();
        UUID otherOwner = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        bound.getOrCreateTag().put("binding", new Binding(otherOwner, "OtherBuilder").serializeNBT());
        if (!WorldCondenserInterfaceBlock.isBoundApprenticeOrb(bound)) {
            helper.fail("A valid bound Apprentice Blood Orb did not satisfy condenser attunement");
            return;
        }
        if (WorldCondenserInterfaceBlock.isBoundApprenticeOrb(new ItemStack(Blocks.STONE))) {
            helper.fail("A non-orb item satisfied condenser attunement");
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void statePacketCodecIsBounded(final GameTestHelper helper) {
        List<String> biomes = IntStream.range(0, PrestigeLimits.MAX_BIOMES)
                .mapToObj(index -> "fixture:biome_" + index).toList();
        var packet = new PrestigeNetwork.StatePacket(PrestigeNetwork.ViewKind.RESET, "", "draft", "world",
                BlockPos.ZERO, 2, 1, biomes.get(0), "Builder", true, biomes, List.of(), List.of());
        FriendlyByteBuf roundTrip = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PrestigeNetwork.StatePacket.encode(packet, roundTrip);
            if (!packet.equals(PrestigeNetwork.StatePacket.decode(roundTrip))) {
                helper.fail("World Condenser state packet failed a boundary round trip");
                return;
            }
        } finally {
            roundTrip.release();
        }

        FriendlyByteBuf oversized = new FriendlyByteBuf(Unpooled.buffer());
        try {
            oversized.writeEnum(PrestigeNetwork.ViewKind.RESET);
            oversized.writeUtf("", 256);
            oversized.writeUtf("draft", 64);
            oversized.writeUtf("world", 128);
            oversized.writeBlockPos(BlockPos.ZERO);
            oversized.writeLong(0);
            oversized.writeLong(0);
            oversized.writeUtf("fixture:biome", 256);
            oversized.writeUtf("Builder", 32);
            oversized.writeBoolean(false);
            oversized.writeVarInt(PrestigeLimits.MAX_BIOMES + 1);
            try {
                PrestigeNetwork.StatePacket.decode(oversized);
                helper.fail("World Condenser accepted an oversized state packet collection");
                return;
            } catch (IllegalArgumentException expected) {
                // Expected: reject before allocating the declared collection.
            }
        } finally {
            oversized.release();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void createSafeNbtIsPreservedWithoutUnknownPayloads(final GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockState state = AllBlocks.CREATIVE_MOTOR.getDefaultState();
        helper.getLevel().setBlockAndUpdate(pos, state);
        if (!(helper.getLevel().getBlockEntity(pos) instanceof CreativeMotorBlockEntity motor)) {
            helper.fail("Create creative motor did not provide its block entity");
            return;
        }
        motor.generatedSpeed.setValue(64);
        CompoundTag source = motor.saveWithFullMetadata();
        source.putString("Items", "must-not-cross");
        source.putLong("Energy", Long.MAX_VALUE);
        source.putString("PrestigeUnsafeMarker", "must-not-cross");
        CompoundTag safe = SchematicLibrary.safeBlockEntityNbt(helper.getLevel().getServer(), pos, state, source);
        if (safe == null || safe.contains("Items") || safe.contains("Energy") || safe.contains("PrestigeUnsafeMarker")) {
            helper.fail("Create-safe schematic NBT retained an unapproved payload");
            return;
        }
        if (safe.getAllKeys().size() <= 4) {
            helper.fail("Create-safe schematic NBT discarded all approved motor configuration");
            return;
        }
        helper.succeed();
    }
}
