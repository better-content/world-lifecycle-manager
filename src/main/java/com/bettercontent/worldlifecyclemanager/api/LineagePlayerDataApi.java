package com.bettercontent.worldlifecyclemanager.api;

import com.bettercontent.worldlifecyclemanager.LineagePlayerDataStore;
import com.bettercontent.worldlifecyclemanager.PrestigeService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Small namespaced persistence surface for state which belongs to a player lineage rather than a world.
 * Integrated servers bind each independently created save to durable profile-level lineage storage without
 * enabling Prestige resets; a future successor may inherit that save's binding.
 */
public final class LineagePlayerDataApi {
    private LineagePlayerDataApi() {}

    public static CompoundTag read(MinecraftServer server, ResourceLocation key, UUID playerId) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(playerId, "playerId");
        var lineage = PrestigeService.lineage(server);
        return LineagePlayerDataStore.read(PrestigeService.lineagePlayerState(server), lineage.lineageId(), key, playerId);
    }

    public static void write(MinecraftServer server, ResourceLocation key, UUID playerId, CompoundTag payload) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(payload, "payload");
        var lineage = PrestigeService.lineage(server);
        LineagePlayerDataStore.write(PrestigeService.lineagePlayerState(server), lineage.lineageId(), key, playerId, payload);
    }
}
