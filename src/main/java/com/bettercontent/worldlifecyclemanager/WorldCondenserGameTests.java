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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import wayoftime.bloodmagic.core.data.Binding;

import java.util.List;
import java.util.UUID;
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

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void schematicMintCreatesPersistentInventory(final GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.getLevel().setBlockAndUpdate(pos, PrestigeRegistry.SCHEMATIC_MINT.get().defaultBlockState());
        if (!(helper.getLevel().getBlockEntity(pos) instanceof SchematicMintBlockEntity mint)) {
            helper.fail("Schematic Mint did not create its block entity"); return;
        }
        mint.setItem(0, new ItemStack(net.minecraft.world.item.Items.PAPER));
        CompoundTag saved = mint.saveWithFullMetadata();
        SchematicMintBlockEntity restored = new SchematicMintBlockEntity(pos, PrestigeRegistry.SCHEMATIC_MINT.get().defaultBlockState());
        restored.load(saved);
        if (restored.getItem(0).getItem() != net.minecraft.world.item.Items.PAPER) {
            helper.fail("Schematic Mint input did not survive NBT persistence"); return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void schematicMintRecipesAreShapelessAndExact(final GameTestHelper helper) {
        net.minecraft.world.SimpleContainer input = new net.minecraft.world.SimpleContainer(3);
        input.setItem(0, new ItemStack(net.minecraft.world.item.Items.LIGHT_BLUE_DYE));
        input.setItem(2, new ItemStack(net.minecraft.world.item.Items.PAPER));
        var recipe = helper.getLevel().getRecipeManager().getRecipeFor(PrestigeRegistry.SCHEMATIC_MINTING.get(), input, helper.getLevel());
        if (recipe.isEmpty() || !recipe.get().getId().toString().equals("world_lifecycle_manager:empty_schematic")) {
            helper.fail("Schematic Mint did not match its shapeless empty-schematic recipe"); return;
        }
        input.setItem(1, new ItemStack(net.minecraft.world.item.Items.FEATHER));
        if (helper.getLevel().getRecipeManager().getRecipeFor(PrestigeRegistry.SCHEMATIC_MINTING.get(), input, helper.getLevel()).isPresent()) {
            helper.fail("Schematic Mint accepted an unrelated extra ingredient"); return;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = PrestigeMod.MOD_ID, template = "empty", timeoutTicks = 100)
    public static void schematicMintHasMechanicalCraftingRecipe(final GameTestHelper helper) {
        ResourceLocation recipeId = new ResourceLocation(PrestigeMod.MOD_ID, "schematic_mint");
        var loaded = helper.getLevel().getRecipeManager().byKey(recipeId);
        if (loaded.isEmpty()) {
            helper.fail("Schematic Mint mechanical-crafting recipe was not loaded"); return;
        }
        var recipe = loaded.get();
        ResourceLocation typeId = ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        if (!new ResourceLocation("create", "mechanical_crafting").equals(typeId)) {
            helper.fail("Schematic Mint recipe did not use Create mechanical crafting"); return;
        }
        var ingredients = recipe.getIngredients();
        if (ingredients.size() != 9) {
            helper.fail("Schematic Mint recipe did not occupy a full 3x3 grid"); return;
        }
        String[] expected = {
                "create:brass_sheet", "create:cogwheel", "create:brass_sheet",
                "create:cogwheel", "create:brass_casing", "create:cogwheel",
                "create:brass_sheet", "create:cogwheel", "create:brass_sheet"
        };
        for (int index = 0; index < expected.length; index++) {
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(expected[index]));
            if (item == null || !ingredients.get(index).test(new ItemStack(item))) {
                helper.fail("Schematic Mint recipe ingredient mismatch at slot " + index); return;
            }
        }
        if (!recipe.getResultItem(helper.getLevel().registryAccess()).is(PrestigeRegistry.SCHEMATIC_MINT_ITEM.get())) {
            helper.fail("Schematic Mint recipe produced the wrong item"); return;
        }
        helper.succeed();
    }

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
                BlockPos.ZERO, 2, 1, List.of(biomes.get(0), biomes.get(1)), "Builder", true,
                biomes, List.of(), List.of(), List.of("biome_selection"), 2);
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
