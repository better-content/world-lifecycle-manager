package com.bettercontent.worldlifecyclemanager;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PrestigeCoordinator {
    private static int stopCountdown = -1;
    private static int shutdownPoll = 0;

    private PrestigeCoordinator() {}

    public static void scheduleStop() { stopCountdown = 20; }

    @SubscribeEvent public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PrestigeNetwork.sendManifest(player);
    }
    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PrestigeNetwork.cancelSync(player);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("world_lifecycle_manager")
                .then(Commands.literal("status").executes(context -> {
                    try {
                        var lineage = PrestigeService.lineage(context.getSource().getServer());
                        context.getSource().sendSuccess(() -> Component.literal("Prestige generation=" + lineage.generation()
                                + " total=" + lineage.totalPrestiges()), false);
                        return 1;
                    } catch (Exception error) {
                        context.getSource().sendFailure(Component.literal("Prestige status failed: " + error.getMessage()));
                        return 0;
                    }
                }))
                .then(Commands.literal("gui")
                        .executes(context -> openGui(context.getSource().getPlayerOrException(), 0))
                        .then(Commands.argument("tab", StringArgumentType.word()).executes(context ->
                                openGui(context.getSource().getPlayerOrException(), tab(StringArgumentType.getString(context, "tab")))))
                        .then(Commands.argument("player", EntityArgument.player()).requires(source -> source.hasPermission(4))
                                .then(Commands.argument("tab", StringArgumentType.word()).executes(context -> openGui(
                                        EntityArgument.getPlayer(context, "player"), tab(StringArgumentType.getString(context, "tab")))))))
                .then(Commands.literal("perks")
                        .executes(context -> {
                            try {
                                var build = PrestigePerks.draft(context.getSource().getServer());
                                context.getSource().sendSuccess(() -> Component.literal("Upcoming prestige perks "
                                        + build.perks().size() + "/" + build.budget() + ": " + String.join(", ", build.ids())), false);
                                return 1;
                            } catch (Exception error) { context.getSource().sendFailure(Component.literal("Prestige perks failed: " + error.getMessage())); return 0; }
                        })
                        .then(Commands.literal("allocate").requires(source -> source.hasPermission(4))
                                .then(Commands.argument("perk", StringArgumentType.word()).executes(context -> perkMutation(context, true))))
                        .then(Commands.literal("refund").requires(source -> source.hasPermission(4))
                                .then(Commands.argument("perk", StringArgumentType.word()).executes(context -> perkMutation(context, false)))))
                .then(Commands.literal("landing").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
                            try { PrestigePerks.setLanding(context.getSource().getServer(), StringArgumentType.getString(context, "mode")); return 1; }
                            catch (Exception error) { context.getSource().sendFailure(Component.literal("Landing selection failed: " + error.getMessage())); return 0; }
                        })))
                .then(Commands.literal("fallback").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("biome-or-clear", StringArgumentType.word()).executes(context -> {
                            try { PrestigePerks.setFallback(context.getSource().getServer(), StringArgumentType.getString(context, "biome-or-clear")); return 1; }
                            catch (Exception error) { context.getSource().sendFailure(Component.literal("Fallback selection failed: " + error.getMessage())); return 0; }
                        })))
                .then(Commands.literal("select").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("biome", StringArgumentType.word()).executes(context -> {
                            try {
                                String biome = StringArgumentType.getString(context, "biome");
                                PrestigeService.saveDraft(context.getSource().getServer(), biome, "Operator");
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Selected Prestige biome " + biome + "; stage with /world_lifecycle_manager stage"), true);
                                return 1;
                            } catch (Exception error) {
                                context.getSource().sendFailure(Component.literal("Prestige selection failed: " + error.getMessage()));
                                return 0;
                            }
                        })))
                .then(Commands.literal("stage").requires(source -> source.hasPermission(4))
                        .executes(context -> {
                            try {
                                PrestigeService.stage(context.getSource().getServer());
                                context.getSource().sendSuccess(() -> Component.literal("Staged prestige reset; commit with /world_lifecycle_manager commit "
                                        + PrestigeService.worldName(context.getSource().getServer())), true);
                                return 1;
                            } catch (Exception error) {
                                context.getSource().sendFailure(Component.literal("Prestige stage failed: " + error.getMessage()));
                                return 0;
                            }
                        }))
                .then(Commands.literal("cancel").requires(source -> source.hasPermission(4)).executes(context -> {
                    try {
                        PrestigeService.cancel(context.getSource().getServer());
                        context.getSource().sendSuccess(() -> Component.literal("Cancelled staged prestige request"), true);
                        return 1;
                    } catch (Exception error) {
                        context.getSource().sendFailure(Component.literal("Prestige cancel failed: " + error.getMessage()));
                        return 0;
                    }
                }))
                .then(Commands.literal("commit").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("world-name", StringArgumentType.word()).executes(context -> {
                            try {
                                String transaction = PrestigeService.commit(context.getSource().getServer(),
                                        StringArgumentType.getString(context, "world-name"));
                                context.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                                        "Prestige committed " + transaction + " by operator command; all world and player state "
                                                + "will be archived and reset."), false);
                                return 1;
                            } catch (Exception error) {
                                context.getSource().sendFailure(Component.literal("Prestige commit failed: " + error.getMessage()));
                                return 0;
                            }
                        })))
                .then(Commands.literal("recovery").requires(source -> source.hasPermission(4))
                        .then(Commands.literal("cancel-staged").executes(context -> {
                            try {
                                Files.deleteIfExists(PrestigeService.control(context.getSource().getServer()).resolve("staged-request-v4.tsv"));
                                PrestigePerks.cancel(context.getSource().getServer());
                                context.getSource().sendSuccess(() -> Component.literal("Cancelled staged prestige request"), true);
                                return 1;
                            } catch (Exception error) {
                                context.getSource().sendFailure(Component.literal("Recovery cancel failed: " + error.getMessage()));
                                return 0;
                            }
                        }))));
    }

    private static int perkMutation(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                                    boolean allocate) {
        try {
            String id = StringArgumentType.getString(context, "perk");
            if (allocate) PrestigePerks.allocate(context.getSource().getServer(), id);
            else PrestigePerks.refund(context.getSource().getServer(), id);
            context.getSource().sendSuccess(() -> Component.literal((allocate ? "Allocated " : "Refunded ") + id), true);
            return 1;
        } catch (Exception error) {
            context.getSource().sendFailure(Component.literal("Prestige perk change failed: " + error.getMessage()));
            return 0;
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        PrestigeNetwork.tickSync(server);
        Path successorPath = PrestigeService.control(server).resolve("successor-request-v4.tsv");
        if (!Files.isRegularFile(successorPath)) return;
        try {
            PrestigeContracts.Successor successor = PrestigeContracts.readSuccessor(successorPath);
            PrestigePerks.Build perks = PrestigePerks.reset(server, successor);
            PrestigeContracts.Lineage lineage = PrestigeService.lineage(server);
            if (!successor.lineageId().equals(lineage.lineageId())
                    || successor.baseGeneration() != lineage.generation()
                    || successor.targetGeneration() != lineage.generation() + 1) {
                throw new IllegalStateException("successor request does not match the active lineage generation");
            }
            ServerLevel level = server.overworld();
            LandingResult landing = resolveLanding(level, successor, perks);
            boolean foundExact = landing != null;
            BlockPos spawn = foundExact ? landing.pos() : level.getSharedSpawnPos();
            if (foundExact) level.setDefaultSpawnPos(spawn, 0.0F);
            String actualBiome = level.getBiome(spawn).unwrapKey()
                    .map(key -> key.location().toString()).orElse("minecraft:the_void");
            boolean fresh = freshDirectory(server.getWorldPath(LevelResource.PLAYER_DATA_DIR))
                    && freshDirectory(server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR))
                    && freshDirectory(server.getWorldPath(LevelResource.PLAYER_STATS_DIR))
                    && server.getPlayerCount() == 0 && foundExact;
            if (!Files.isRegularFile(server.getWorldPath(LevelResource.LEVEL_DATA_FILE))) {
                throw new IllegalStateException("successor level.dat is missing");
            }
            PrestigeContracts.writeHealth(PrestigeService.control(server).resolve("health-result-v4.tsv"), successor,
                    level.getSeed(), actualBiome, PrestigeService.worldName(server), fresh, foundExact);
            PrestigePerks.writeHealth(server, successor, perks, foundExact ? landing.resolvedTarget() : "none",
                    foundExact && landing.safe());
            server.sendSystemMessage(Component.literal("Prestige successor health published for " + successor.transactionId()
                    + " biome=" + actualBiome));
        } catch (Exception error) {
            server.sendSystemMessage(Component.literal("Prestige successor health failed: " + error.getMessage()));
        }
    }

    private record LandingResult(BlockPos pos, String resolvedTarget, boolean safe) {}

    private static LandingResult resolveLanding(ServerLevel level, PrestigeContracts.Successor successor,
                                                PrestigePerks.Build perks) {
        BlockPos candidate;
        String target;
        if (perks.landing() == PrestigePerks.Landing.VILLAGE) {
            BlockPos found = level.findNearestMapStructure(StructureTags.VILLAGE,
                    level.getSharedSpawnPos(), PrestigePerks.VILLAGE_RADIUS_CHUNKS, false);
            if (found == null) return null;
            candidate = found;
            target = "village";
        } else {
            target = successor.biome();
            Pair<BlockPos, Holder<Biome>> found = findBiome(level, target);
            if (found == null && !perks.fallbackBiome().isEmpty()) {
                target = perks.fallbackBiome();
                found = findBiome(level, target);
            }
            if (found == null) return null;
            candidate = found.getFirst();
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, candidate.getX(), candidate.getZ());
        BlockPos surface = new BlockPos(candidate.getX(), y, candidate.getZ());
        if (!perks.has(PrestigePerks.Perk.SAFE_ARRIVAL)) return new LandingResult(surface, target, false);
        BlockPos safe = findSafe(level, surface, perks.landing() == PrestigePerks.Landing.BIOME ? target : null);
        return safe == null ? null : new LandingResult(safe, target, true);
    }

    private static Pair<BlockPos, Holder<Biome>> findBiome(ServerLevel level, String id) {
        ResourceLocation requested = new ResourceLocation(id);
        return level.findClosestBiome3d(holder -> holder.unwrapKey().map(key -> key.location().equals(requested)).orElse(false),
                level.getSharedSpawnPos(), 16_384, 32, 64);
    }

    private static BlockPos findSafe(ServerLevel level, BlockPos center, String requiredBiome) {
        for (int radius = 0; radius <= PrestigePerks.SAFE_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                BlockPos north = safeAt(level, center.getX() + dx, center.getZ() - radius, requiredBiome);
                if (north != null) return north;
                if (radius > 0) { BlockPos south = safeAt(level, center.getX() + dx, center.getZ() + radius, requiredBiome); if (south != null) return south; }
            }
            for (int dz = -radius + 1; dz < radius; dz++) {
                BlockPos west = safeAt(level, center.getX() - radius, center.getZ() + dz, requiredBiome);
                if (west != null) return west;
                if (radius > 0) { BlockPos east = safeAt(level, center.getX() + radius, center.getZ() + dz, requiredBiome); if (east != null) return east; }
            }
        }
        return null;
    }

    private static BlockPos safeAt(ServerLevel level, int x, int z, String requiredBiome) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos feet = new BlockPos(x, y, z);
        if (requiredBiome != null && !level.getBiome(feet).unwrapKey().map(key -> key.location().toString().equals(requiredBiome)).orElse(false)) return null;
        BlockPos floor = feet.below(); var floorState = level.getBlockState(floor);
        if (!floorState.isFaceSturdy(level, floor, net.minecraft.core.Direction.UP) || floorState.is(BlockTags.LEAVES)
                || floorState.is(BlockTags.FIRE) || !level.getFluidState(floor).isEmpty()) return null;
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() || !level.getFluidState(feet).isEmpty()) return null;
        BlockPos head = feet.above();
        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty() || !level.getFluidState(head).isEmpty()) return null;
        return feet;
    }

    private static int tab(String raw) {
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "reset" -> 0;
            case "schematics", "schematic" -> 1;
            case "perks", "perk" -> 2;
            default -> throw new IllegalArgumentException("GUI tab must be reset, schematics, or perks");
        };
    }

    private static int openGui(ServerPlayer player, int tab) {
        NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (id, inventory, ignored) -> new WorldCondenserMenu(id, inventory, BlockPos.ZERO, true, tab),
                Component.literal("Prestige")), buffer -> {
            buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeBoolean(true);
            buffer.writeVarInt(tab);
        });
        return 1;
    }

    private static boolean freshDirectory(Path path) throws java.io.IOException {
        if (!Files.exists(path)) return true;
        if (!Files.isDirectory(path)) return false;
        try (var entries = Files.list(path)) { return entries.findAny().isEmpty(); }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (stopCountdown >= 0 && --stopCountdown <= 0) {
            stopCountdown = -1;
            server.halt(false);
            return;
        }
        if (++shutdownPoll < 20) return;
        shutdownPoll = 0;
        Path shutdownPath = PrestigeService.control(server).resolve("shutdown-request-v4.tsv");
        if (!Files.isRegularFile(shutdownPath)) return;
        try {
            String transaction = PrestigeContracts.readShutdownTransaction(shutdownPath);
            Path activePath = PrestigeService.control(server).resolve("active-successor-process-v1.tsv");
            if (Files.isRegularFile(activePath)
                    && PrestigeContracts.readActiveSuccessor(activePath).transactionId().equals(transaction)) {
                Files.deleteIfExists(shutdownPath);
                server.saveEverything(true, true, true);
                server.halt(false);
            }
        } catch (Exception error) {
            server.sendSystemMessage(Component.literal("Ignoring invalid prestige shutdown request: " + error.getMessage()));
        }
    }
}
