package com.bettercontent.prestige;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class PrestigeClientState {
    private static PrestigeNetwork.StatePacket state;
    private static int revision;

    private PrestigeClientState() {}
    public static PrestigeNetwork.StatePacket state() { return state; }
    public static int revision() { return revision; }

    public static void accept(PrestigeNetwork.StatePacket next) { state = next; revision++; }
    public static void clear() { state = null; revision++; }

    public static void saveDownload(PrestigeNetwork.DownloadPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packet.data()));
            if (!digest.equals(packet.sha256())) throw new IOException("download hash mismatch");
            String safeAuthor = packet.author().replaceAll("[^A-Za-z0-9_]", "_");
            String safeName = packet.name().replaceAll("[^A-Za-z0-9._ -]", "_");
            if (!safeName.endsWith(".nbt")) safeName += ".nbt";
            Path directory = FMLPaths.GAMEDIR.get().resolve("schematics/lineage").resolve(safeAuthor).normalize();
            Files.createDirectories(directory);
            Path target = directory.resolve(safeName).normalize();
            if (!target.startsWith(directory)) throw new IOException("download path escaped schematic directory");
            if (Files.exists(target)) {
                String stem = safeName.substring(0, safeName.length() - 4);
                target = directory.resolve(stem + "-" + digest.substring(0, 8) + ".nbt");
            }
            Files.write(target, packet.data(), StandardOpenOption.CREATE_NEW);
            if (minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.literal("Downloaded lineage schematic to " + directory.relativize(target)), false);
        } catch (Exception error) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.literal("Schematic download failed: " + error.getMessage()), false);
        }
    }
}
