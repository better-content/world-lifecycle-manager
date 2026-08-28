package com.bettercontent.worldlifecyclemanager;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.bettercontent.worldlifecyclemanager.PrestigeMod;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.stream.IntStream;

@PrefixGameTestTemplate(false)
public final class WorldCondenserGameTests {
    private WorldCondenserGameTests() {}

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void successorSpawnIsExactAndRetained(final GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos previousSpawn = level.getSharedSpawnPos();
        int previousRadius = level.getGameRules().getInt(GameRules.RULE_SPAWN_RADIUS);
        BlockPos target = helper.absolutePos(new BlockPos(2, 2, 2));
        PrestigeCoordinator.configureSuccessorSpawn(server, level, target);
        if (!level.getSharedSpawnPos().equals(target)
                || level.getGameRules().getInt(GameRules.RULE_SPAWN_RADIUS) != 0) {
            helper.fail("Successor landing did not become the exact zero-radius world spawn");
            return;
        }
        level.getChunkSource().removeRegionTicket(TicketType.START, new ChunkPos(target), 11, Unit.INSTANCE);
        level.setDefaultSpawnPos(previousSpawn, 0.0F);
        level.getChunkSource().addRegionTicket(TicketType.START, new ChunkPos(previousSpawn), 11, Unit.INSTANCE);
        level.getGameRules().getRule(GameRules.RULE_SPAWN_RADIUS).set(previousRadius, server);
        helper.succeed();
    }

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 200)
    public static void standaloneInterfaceRequiresNoStructureOrAttunement(final GameTestHelper helper) {
        BlockPos interfacePos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockState interfaceState = PrestigeRegistry.WORLD_CONDENSER_INTERFACE.get().defaultBlockState()
                .setValue(WorldCondenserInterfaceBlock.FACING, Direction.NORTH);
        helper.getLevel().setBlockAndUpdate(interfacePos, interfaceState);
        if (!(helper.getLevel().getBlockEntity(interfacePos) instanceof WorldCondenserBlockEntity entity)) {
            helper.fail("World Condenser interface did not create its block entity");
            return;
        }
        CompoundTag saved = entity.saveWithFullMetadata();
        if (saved.contains("Attuned")) {
            helper.fail("Standalone World Condenser retained obsolete attunement state");
            return;
        }
        if (!WorldCondenserInterfaceBlock.hasOperatorPermission(4)
                || WorldCondenserInterfaceBlock.hasOperatorPermission(3)) {
            helper.fail("World Condenser operator permission threshold is not level 4");
            return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void statePacketCodecIsBounded(final GameTestHelper helper) {
        List<String> biomes = IntStream.range(0, PrestigeLimits.MAX_BIOMES)
                .mapToObj(index -> "fixture:biome_" + index).toList();
        var packet = new PrestigeNetwork.StatePacket(PrestigeNetwork.ViewKind.RESET, "", "draft", "world",
                BlockPos.ZERO, 2, 1, List.of(biomes.get(0), biomes.get(1)), "Builder", true,
                PrestigeService.Recovery.NONE, biomes, List.of(), List.of("biome_selection"), 2);
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
            oversized.writeVarInt(4);
            oversized.writeUtf("fixture:biome_1", 256);
            oversized.writeUtf("fixture:biome_2", 256);
            oversized.writeUtf("fixture:biome_3", 256);
            oversized.writeUtf("fixture:biome_4", 256);
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
        var manifest = new PrestigeNetwork.SyncManifestPacket(List.of(new PrestigeNetwork.ClientEntry(
                "entry", "Builder", "plan.nbt", 12, "a".repeat(64))));
        FriendlyByteBuf manifestRoundTrip = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PrestigeNetwork.SyncManifestPacket.encode(manifest, manifestRoundTrip);
            if (!manifest.equals(PrestigeNetwork.SyncManifestPacket.decode(manifestRoundTrip))) {
                helper.fail("Schematic sync manifest failed a round trip"); return;
            }
        } finally { manifestRoundTrip.release(); }
        byte[] publicationBytes = new byte[]{0x1f, (byte) 0x8b, 1, 2, 3};
        var publication = new PrestigeNetwork.PublishPacket(BlockPos.ZERO, "plan.nbt", publicationBytes);
        FriendlyByteBuf publicationRoundTrip = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PrestigeNetwork.PublishPacket.encode(publication, publicationRoundTrip);
            PrestigeNetwork.PublishPacket decoded = PrestigeNetwork.PublishPacket.decode(publicationRoundTrip);
            if (!publication.pos().equals(decoded.pos()) || !publication.name().equals(decoded.name())
                    || !java.util.Arrays.equals(publicationBytes, decoded.compressedNbt())) {
                helper.fail("Schematic publication packet failed a boundary round trip"); return;
            }
        } finally { publicationRoundTrip.release(); }
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

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void schematicannonSubstitutionRulesPersist(final GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(pos, AllBlocks.SCHEMATICANNON.getDefaultState());
        if (!(helper.getLevel().getBlockEntity(pos) instanceof SchematicannonBlockEntity cannon)) {
            helper.fail("Create Schematicannon did not create its block entity"); return;
        }
        var access = (SchematicannonSubstitutionAccess) cannon;
        var source = new ResourceLocation("minecraft", "stone");
        var target = new ResourceLocation("minecraft", "cobblestone");
        access.worldLifecycleManager$setSubstitution(source, target);
        CompoundTag saved = cannon.getUpdateTag();
        access.worldLifecycleManager$clearSubstitutions();
        cannon.handleUpdateTag(saved);
        if (!target.equals(access.worldLifecycleManager$substitutions().get(source))) {
            helper.fail("Schematicannon substitution rule did not survive NBT synchronization"); return;
        }
        helper.succeed();
    }
}
