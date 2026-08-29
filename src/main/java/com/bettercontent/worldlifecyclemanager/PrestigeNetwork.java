package com.bettercontent.worldlifecyclemanager;

import com.bettercontent.worldlifecyclemanager.PrestigeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PrestigeNetwork {
    private static final String VERSION = "7";
    private static final int REFRESH_CACHE_TICKS = 10;
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(PrestigeMod.MOD_ID, "world_lifecycle_manager"))
            .networkProtocolVersion(() -> VERSION).clientAcceptedVersions(VERSION::equals)
            .serverAcceptedVersions(VERSION::equals).simpleChannel();
    private static int discriminator;
    private static final Map<ServerPlayer, CachedState> STATE_CACHE = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> LAST_PHYSICAL_OPEN = new WeakHashMap<>();
    private static final Map<UUID, SyncBatch> SYNC_QUEUES = new java.util.HashMap<>();

    private PrestigeNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(ActionPacket.class, discriminator++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ActionPacket::encode).decoder(ActionPacket::decode).consumerMainThread(ActionPacket::handle).add();
        CHANNEL.messageBuilder(PublishPacket.class, discriminator++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PublishPacket::encode).decoder(PublishPacket::decode).consumerMainThread(PublishPacket::handle).add();
        CHANNEL.messageBuilder(StatePacket.class, discriminator++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StatePacket::encode).decoder(StatePacket::decode).consumerMainThread(StatePacket::handle).add();
        CHANNEL.messageBuilder(DownloadPacket.class, discriminator++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DownloadPacket::encode).decoder(DownloadPacket::decode).consumerMainThread(DownloadPacket::handle).add();
        CHANNEL.messageBuilder(SyncManifestPacket.class, discriminator++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncManifestPacket::encode).decoder(SyncManifestPacket::decode).consumerMainThread(SyncManifestPacket::handle).add();
        CHANNEL.messageBuilder(SyncRequestPacket.class, discriminator++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SyncRequestPacket::encode).decoder(SyncRequestPacket::decode).consumerMainThread(SyncRequestPacket::handle).add();
    }

    public static void sendAction(Action action, BlockPos pos, String value) {
        CHANNEL.sendToServer(new ActionPacket(action, pos, value));
    }
    public static void sendPublish(BlockPos pos, String name, byte[] compressedNbt) {
        CHANNEL.sendToServer(new PublishPacket(pos, name, compressedNbt));
    }
    public static void requestAutomaticSync(List<String> ids) { CHANNEL.sendToServer(new SyncRequestPacket(ids)); }
    public static void sendManifest(ServerPlayer player) {
        if (!PrestigeService.supportsPrestigeReset(player.server)) return;
        try {
            List<ClientEntry> entries = SchematicLibrary.list(player.server).stream().map(entry -> new ClientEntry(
                    entry.id(), entry.author(), entry.originalName(), entry.size(), entry.sha256())).toList();
            CHANNEL.sendTo(new SyncManifestPacket(entries), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        } catch (Exception error) { PrestigeMod.LOGGER.warn("Could not build schematic manifest for {}: {}", player.getScoreboardName(), error.getMessage()); }
    }
    public static void cancelSync(ServerPlayer player) { SYNC_QUEUES.remove(player.getUUID()); }
    public static void tickSync(net.minecraft.server.MinecraftServer server) {
        if (!PrestigeService.supportsPrestigeReset(server)) {
            SYNC_QUEUES.clear();
            return;
        }
        for (var iterator = SYNC_QUEUES.entrySet().iterator(); iterator.hasNext();) {
            var queued = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(queued.getKey());
            SyncBatch batch = queued.getValue();
            if (player == null || batch.entries.isEmpty()) { iterator.remove(); continue; }
            SchematicLibrary.Entry entry = batch.entries.removeFirst();
            int ordinal = ++batch.sent;
            try {
                byte[] data = SchematicLibrary.download(server, entry.id());
                CHANNEL.sendTo(new DownloadPacket(entry.author(), entry.originalName(), entry.sha256(), data, true, ordinal, batch.total),
                        player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            } catch (Exception error) { PrestigeMod.LOGGER.warn("Automatic schematic sync failed for {}: {}", player.getScoreboardName(), error.getMessage()); }
            if (batch.entries.isEmpty()) iterator.remove();
        }
    }

    public static boolean allowPhysicalOpen(ServerPlayer player) {
        long now = player.server.overworld().getGameTime();
        Long previous = LAST_PHYSICAL_OPEN.put(player, now);
        return previous == null || now < previous || now - previous >= REFRESH_CACHE_TICKS;
    }

    public static void sendState(ServerPlayer player, ViewKind view, boolean allowCached) {
        BlockPos pos = player.containerMenu instanceof WorldCondenserMenu menu ? menu.pos() : BlockPos.ZERO;
        long now = player.server.overworld().getGameTime();
        CachedState cached = STATE_CACHE.get(player);
        if (allowCached && cached != null && cached.view == view && cached.pos.equals(pos)
                && now >= cached.tick && now - cached.tick < REFRESH_CACHE_TICKS) {
            send(player, cached.packet);
            return;
        }
        try {
            StatePacket packet = new StatePacket(PrestigeService.view(player, view == ViewKind.SCHEMATICS), pos, view);
            STATE_CACHE.put(player, new CachedState(now, pos, view, packet));
            send(player, packet);
        } catch (Exception error) {
            String message = safeError(error);
            PrestigeMod.LOGGER.warn("World Condenser state failed for {}: {}", player.getScoreboardName(), message);
            StatePacket packet = StatePacket.failure(pos, view, message, player.hasPermissions(4),
                    PrestigeService.diagnoseRecovery(player.server).action());
            STATE_CACHE.put(player, new CachedState(now, pos, view, packet));
            send(player, packet);
        }
    }

    private static void send(ServerPlayer player, StatePacket packet) {
        CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 256 ? message : message.substring(0, 255) + "…";
    }

    private record CachedState(long tick, BlockPos pos, ViewKind view, StatePacket packet) {}
    public enum ViewKind { RESET, SCHEMATICS, PERKS }
    public enum Action { REFRESH_RESET, REFRESH_SCHEMATICS, REFRESH_PERKS, SET_BIOME_1, SET_BIOME_2, SET_BIOME_3,
        TOGGLE_PERK, STAGE, CANCEL, COMMIT, RECOVER_INVALID, DOWNLOAD, REMOVE }

    public record ActionPacket(Action action, BlockPos pos, String value) {
        static void encode(ActionPacket packet, FriendlyByteBuf buffer) {
            buffer.writeEnum(packet.action); buffer.writeBlockPos(packet.pos); buffer.writeUtf(packet.value, 256);
        }
        static ActionPacket decode(FriendlyByteBuf buffer) {
            return new ActionPacket(buffer.readEnum(Action.class), buffer.readBlockPos(), buffer.readUtf(256));
        }
        static void handle(ActionPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.setPacketHandled(true);
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof WorldCondenserMenu menu) || !menu.pos().equals(packet.pos)) return;
            if (!PrestigeService.supportsPrestigeReset(player.server)) {
                player.displayClientMessage(Component.literal("Prestige is disabled in single-player."), false);
                return;
            }
            try {
                switch (packet.action) {
                    case REFRESH_RESET -> sendState(player, ViewKind.RESET, true);
                    case REFRESH_SCHEMATICS -> sendState(player, ViewKind.SCHEMATICS, true);
                    case REFRESH_PERKS -> sendState(player, ViewKind.PERKS, true);
                    case SET_BIOME_1 -> PrestigeService.setBiomeSlot(player, 0, packet.value);
                    case SET_BIOME_2 -> PrestigeService.setBiomeSlot(player, 1, packet.value);
                    case SET_BIOME_3 -> PrestigeService.setBiomeSlot(player, 2, packet.value);
                    case TOGGLE_PERK -> PrestigePerks.toggle(player, packet.value);
                    case STAGE -> {
                        requirePhysicalMenu(player, menu);
                        PrestigeService.stage(player, packet.pos);
                    }
                    case CANCEL -> PrestigeService.cancel(player);
                    case COMMIT -> {
                        requirePhysicalMenu(player, menu);
                        if (!packet.value.isEmpty()) throw new IllegalArgumentException("commit does not accept a world name");
                        String tx = PrestigeService.commit(player, packet.pos);
                        player.server.getPlayerList().broadcastSystemMessage(Component.literal(
                                "World Condenser committed " + tx + " by " + player.getGameProfile().getName()
                                        + "; all world and player state will be archived and reset."), false);
                    }
                    case RECOVER_INVALID -> {
                        requirePhysicalMenu(player, menu);
                        if (!packet.value.isEmpty()) throw new IllegalArgumentException("recovery does not accept a value");
                        PrestigeService.recoverInvalid(player, packet.pos);
                        player.displayClientMessage(Component.literal("Discarded invalid uncommitted Prestige state."), false);
                    }
                    case DOWNLOAD -> {
                        SchematicLibrary.Entry entry = SchematicLibrary.list(player.server).stream()
                                .filter(candidate -> candidate.id().equals(packet.value)).findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("unknown schematic entry"));
                        byte[] data = SchematicLibrary.download(player.server, entry.id());
                        CHANNEL.sendTo(new DownloadPacket(entry.author(), entry.originalName(), entry.sha256(), data, false, 1, 1),
                                player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                    }
                    case REMOVE -> PrestigeService.remove(player, packet.value);
                }
                if (packet.action != Action.REFRESH_RESET && packet.action != Action.REFRESH_SCHEMATICS
                        && packet.action != Action.REFRESH_PERKS) {
                    PrestigeMod.LOGGER.info("World Condenser action succeeded actor={} action={} pos={}",
                            player.getScoreboardName(), packet.action, packet.pos);
                }
            } catch (Exception error) {
                PrestigeMod.LOGGER.warn("World Condenser action failed actor={} action={} pos={}: {}",
                        player.getScoreboardName(), packet.action, packet.pos, error.getMessage());
                player.displayClientMessage(Component.literal("World Condenser refused: " + error.getMessage()), false);
            }
            switch (packet.action) {
                case SET_BIOME_1, SET_BIOME_2, SET_BIOME_3, STAGE, CANCEL, RECOVER_INVALID -> sendState(player, ViewKind.RESET, false);
                case TOGGLE_PERK -> sendState(player, ViewKind.PERKS, false);
                case REMOVE -> sendState(player, ViewKind.SCHEMATICS, false);
                default -> { }
            }
        }

        private static void requirePhysicalMenu(ServerPlayer player, WorldCondenserMenu menu) {
            if (menu.remote() || !menu.isInOriginalDimension(player) || !menu.stillValid(player)) {
                throw new IllegalStateException("destructive actions require a nearby physical World Condenser menu");
            }
        }
    }

    public record PublishPacket(BlockPos pos, String name, byte[] compressedNbt) {
        public PublishPacket {
            compressedNbt = compressedNbt == null ? null : compressedNbt.clone();
        }

        @Override public byte[] compressedNbt() {
            return compressedNbt == null ? null : compressedNbt.clone();
        }

        static void encode(PublishPacket packet, FriendlyByteBuf buffer) {
            if (packet.compressedNbt == null || packet.compressedNbt.length > SchematicLibrary.MAX_BYTES) {
                throw new IllegalArgumentException("schematic publication payload is outside the size limit");
            }
            buffer.writeBlockPos(packet.pos);
            buffer.writeUtf(packet.name, 256);
            buffer.writeByteArray(packet.compressedNbt);
        }

        static PublishPacket decode(FriendlyByteBuf buffer) {
            return new PublishPacket(buffer.readBlockPos(), buffer.readUtf(256),
                    buffer.readByteArray((int) SchematicLibrary.MAX_BYTES));
        }

        static void handle(PublishPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.setPacketHandled(true);
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof WorldCondenserMenu menu)
                    || !menu.pos().equals(packet.pos)) return;
            if (!PrestigeService.supportsPrestigeReset(player.server)) {
                player.displayClientMessage(Component.literal("Prestige is disabled in single-player."), false);
                return;
            }
            try {
                SchematicLibrary.Entry entry = PrestigeService.publish(player, packet.name, packet.compressedNbt);
                PrestigeMod.LOGGER.info("World Condenser schematic publication succeeded actor={} name={} id={} pos={}",
                        player.getScoreboardName(), entry.originalName(), entry.id(), packet.pos);
                player.displayClientMessage(Component.literal("Published lineage schematic " + entry.originalName()), false);
            } catch (Exception error) {
                String message = safeError(error);
                PrestigeMod.LOGGER.warn("World Condenser schematic publication failed actor={} name={} pos={}: {}",
                        player.getScoreboardName(), packet.name, packet.pos, message);
                player.displayClientMessage(Component.literal("World Condenser refused: " + message), false);
            }
            sendState(player, ViewKind.SCHEMATICS, false);
        }
    }

    public record ClientEntry(String id, String author, String name, long size, String sha256) {}
    public record StatePacket(ViewKind view, String error, String status, String worldName, BlockPos pos, long total, long generation, List<String> selectedBiomes,
                              String author, boolean operator, PrestigeService.Recovery recovery, List<String> biomes,
                              List<ClientEntry> published, List<String> perks, int perkBudget) {
        public StatePacket {
            selectedBiomes = List.copyOf(selectedBiomes);
            if (!selectedBiomes.isEmpty()) PrestigeContracts.validateBiomes(selectedBiomes);
        }
        StatePacket(PrestigeService.View state, BlockPos pos, ViewKind view) {
            this(view, "", state.status(), state.worldName(), pos, state.lineage().totalPrestiges(), state.lineage().generation(),
                    state.selectedBiomes(), state.author(), state.operator(), PrestigeService.Recovery.NONE, state.allowedBiomes(),
                    state.published().stream().map(entry -> new ClientEntry(entry.id(), entry.author(), entry.originalName(),
                            entry.size(), entry.sha256())).toList(), state.perkBuild().ids(), state.perkBuild().budget());
        }
        static StatePacket failure(BlockPos pos, ViewKind view, String error, boolean operator, PrestigeService.Recovery recovery) {
            return new StatePacket(view, error, "unavailable", "", pos, 0, 0, List.of(), "", operator, recovery,
                    List.of(), List.of(), List.of(), 0);
        }
        static void encode(StatePacket packet, FriendlyByteBuf buffer) {
            PrestigeLimits.requireSize("selected biome preferences", packet.selectedBiomes, 3);
            PrestigeLimits.requireSize("biomes", packet.biomes, PrestigeLimits.MAX_BIOMES);
            PrestigeLimits.requireSize("published", packet.published, SchematicLibrary.MAX_PUBLISHED);
            PrestigeLimits.requireSize("perks", packet.perks, PrestigePerks.Perk.values().length);
            buffer.writeEnum(packet.view); buffer.writeUtf(packet.error, 256);
            buffer.writeUtf(packet.status, 64); buffer.writeUtf(packet.worldName, 128); buffer.writeBlockPos(packet.pos);
            buffer.writeLong(packet.total);
            buffer.writeLong(packet.generation);
            buffer.writeCollection(packet.selectedBiomes, (out, value) -> out.writeUtf(value, 256));
            buffer.writeUtf(packet.author, 32);
            buffer.writeBoolean(packet.operator);
            buffer.writeEnum(packet.recovery);
            buffer.writeCollection(packet.biomes, (out, value) -> out.writeUtf(value, 256));
            buffer.writeCollection(packet.published, (out, entry) -> {
                out.writeUtf(entry.id, 64); out.writeUtf(entry.author, 32); out.writeUtf(entry.name, 256);
                out.writeLong(entry.size); out.writeUtf(entry.sha256, 64);
            });
            buffer.writeCollection(packet.perks, (out, value) -> out.writeUtf(value, 32));
            buffer.writeVarInt(packet.perkBudget);
        }
        static StatePacket decode(FriendlyByteBuf buffer) {
            ViewKind view = buffer.readEnum(ViewKind.class); String error = buffer.readUtf(256);
            String status = buffer.readUtf(64); String worldName = buffer.readUtf(128); BlockPos pos = buffer.readBlockPos();
            long total = buffer.readLong();
            long generation = buffer.readLong();
            List<String> selected = readBounded(buffer, 3, in -> in.readUtf(256));
            String author = buffer.readUtf(32);
            boolean operator = buffer.readBoolean();
            PrestigeService.Recovery recovery = buffer.readEnum(PrestigeService.Recovery.class);
            List<String> biomes = readBounded(buffer, PrestigeLimits.MAX_BIOMES, in -> in.readUtf(256));
            List<ClientEntry> entries = readBounded(buffer, SchematicLibrary.MAX_PUBLISHED, in -> new ClientEntry(
                    in.readUtf(64), in.readUtf(32), in.readUtf(256), in.readLong(), in.readUtf(64)));
            List<String> perks = readBounded(buffer, PrestigePerks.Perk.values().length, in -> in.readUtf(32));
            int budget = buffer.readVarInt();
            return new StatePacket(view, error, status, worldName, pos, total, generation, selected, author, operator, recovery,
                    biomes, entries, perks, budget);
        }
        static void handle(StatePacket packet, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().setPacketHandled(true);
            PrestigeClientState.accept(packet);
        }
    }

    private static <T> List<T> readBounded(FriendlyByteBuf buffer, int maximum, Function<FriendlyByteBuf, T> reader) {
        int count = buffer.readVarInt();
        PrestigeLimits.requireCount("packet collection", count, maximum);
        List<T> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(reader.apply(buffer));
        return List.copyOf(result);
    }

    public record SyncManifestPacket(List<ClientEntry> entries) {
        static void encode(SyncManifestPacket packet, FriendlyByteBuf buffer) {
            PrestigeLimits.requireSize("schematic manifest", packet.entries, SchematicLibrary.MAX_PUBLISHED);
            buffer.writeCollection(packet.entries, (out, entry) -> { out.writeUtf(entry.id, 64); out.writeUtf(entry.author, 32); out.writeUtf(entry.name, 256); out.writeLong(entry.size); out.writeUtf(entry.sha256, 64); });
        }
        static SyncManifestPacket decode(FriendlyByteBuf buffer) { return new SyncManifestPacket(readBounded(buffer, SchematicLibrary.MAX_PUBLISHED,
                in -> new ClientEntry(in.readUtf(64), in.readUtf(32), in.readUtf(256), in.readLong(), in.readUtf(64)))); }
        static void handle(SyncManifestPacket packet, Supplier<NetworkEvent.Context> supplier) { supplier.get().setPacketHandled(true); PrestigeClientState.acceptManifest(packet.entries); }
    }
    public record SyncRequestPacket(List<String> ids) {
        static void encode(SyncRequestPacket packet, FriendlyByteBuf buffer) { PrestigeLimits.requireSize("schematic request", packet.ids, SchematicLibrary.MAX_PUBLISHED); buffer.writeCollection(packet.ids, (out, id) -> out.writeUtf(id, 64)); }
        static SyncRequestPacket decode(FriendlyByteBuf buffer) { return new SyncRequestPacket(readBounded(buffer, SchematicLibrary.MAX_PUBLISHED, in -> in.readUtf(64))); }
        static void handle(SyncRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get(); context.setPacketHandled(true); ServerPlayer player = context.getSender(); if (player == null) return;
            if (!PrestigeService.supportsPrestigeReset(player.server)) return;
            HashSet<String> requested = new HashSet<>(packet.ids);
            ArrayDeque<SchematicLibrary.Entry> queue = new ArrayDeque<>();
            try { SchematicLibrary.list(player.server).stream().filter(entry -> requested.contains(entry.id())).forEach(queue::add); }
            catch (Exception error) { PrestigeMod.LOGGER.warn("Could not queue schematic sync for {}: {}", player.getScoreboardName(), error.getMessage()); return; }
            if (!queue.isEmpty()) SYNC_QUEUES.put(player.getUUID(), new SyncBatch(queue));
        }
    }
    public record DownloadPacket(String author, String name, String sha256, byte[] data, boolean automatic, int ordinal, int total) {
        static void encode(DownloadPacket packet, FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.author, 32); buffer.writeUtf(packet.name, 256); buffer.writeUtf(packet.sha256, 64);
            buffer.writeByteArray(packet.data); buffer.writeBoolean(packet.automatic); buffer.writeVarInt(packet.ordinal); buffer.writeVarInt(packet.total);
        }
        static DownloadPacket decode(FriendlyByteBuf buffer) {
            return new DownloadPacket(buffer.readUtf(32), buffer.readUtf(256), buffer.readUtf(64),
                    buffer.readByteArray((int) SchematicLibrary.MAX_BYTES), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
        }
        static void handle(DownloadPacket packet, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().setPacketHandled(true);
            PrestigeClientState.saveDownload(packet);
        }
    }

    private static final class SyncBatch {
        private final ArrayDeque<SchematicLibrary.Entry> entries;
        private final int total;
        private int sent;
        private SyncBatch(ArrayDeque<SchematicLibrary.Entry> entries) { this.entries = entries; this.total = entries.size(); }
    }
}
