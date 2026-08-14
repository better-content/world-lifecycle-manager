package com.bettercontent.worldlifecyclemanager;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class PrestigeClientState {
    private static PrestigeNetwork.StatePacket state;
    private static int revision;
    private static int syncSaved;
    private static int syncPresent;
    private static int syncFailed;

    private PrestigeClientState() {}
    public static PrestigeNetwork.StatePacket state() { return state; }
    public static int revision() { return revision; }

    public static void accept(PrestigeNetwork.StatePacket next) { state = next; revision++; }
    public static void clear() { state = null; revision++; }

    public static void saveDownload(PrestigeNetwork.DownloadPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            var result = SchematicDownloadStore.save(FMLPaths.GAMEDIR.get(), packet.author(), packet.name(), packet.sha256(), packet.data());
            if (packet.automatic()) {
                if (result.status() == SchematicDownloadStore.Status.SAVED) syncSaved++; else syncPresent++;
            } else if (minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.literal((result.status() == SchematicDownloadStore.Status.SAVED ? "Downloaded" : "Already have")
                            + " lineage schematic " + result.path().getFileName()), false);
        } catch (Exception error) {
            if (packet.automatic()) syncFailed++;
            else if (minecraft.player != null) minecraft.player.displayClientMessage(
                    Component.literal("Schematic download failed: " + error.getMessage()), false);
        }
        if (packet.automatic() && packet.ordinal() == packet.total() && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("Lineage schematics synced: " + syncSaved + " added, "
                    + syncPresent + " already present" + (syncFailed == 0 ? "." : ", " + syncFailed + " failed.")), false);
            syncSaved = syncPresent = syncFailed = 0;
        }
    }

    public static void acceptManifest(List<PrestigeNetwork.ClientEntry> entries) {
        Path game = FMLPaths.GAMEDIR.get();
        List<String> missing = entries.stream().filter(entry -> !SchematicDownloadStore.contains(
                game, entry.author(), entry.name(), entry.sha256())).map(PrestigeNetwork.ClientEntry::id).toList();
        if (!missing.isEmpty()) PrestigeNetwork.requestAutomaticSync(missing);
    }
}
