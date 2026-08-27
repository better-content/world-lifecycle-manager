package com.bettercontent.worldlifecyclemanager.harness;

import com.simibubi.create.content.schematics.cannon.SchematicannonScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;

public final class VisualHarnessScreenshot {
    private static String pending;
    private static int settleTicks;
    private static boolean installed;

    private VisualHarnessScreenshot() {}

    static void request(String name) {
        System.out.println("WLM_VISUAL client received capture request: " + name);
        if (!installed) {
            MinecraftForge.EVENT_BUS.register(VisualHarnessScreenshot.class);
            installed = true;
        }
        pending = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        settleTicks = 0;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof SchematicannonScreen) || ++settleTicks < 20) return;
        String fileName = pending.endsWith(".png") ? pending : pending + ".png";
        pending = null;
        Screenshot.grab(minecraft.gameDirectory, fileName, minecraft.getMainRenderTarget(), message -> {
            Path output = minecraft.gameDirectory.toPath().resolve("screenshots").resolve(fileName);
            if (!Files.isRegularFile(output)) throw new IllegalStateException("WLM visual screenshot failed: " + message.getString());
            System.out.println("WLM_VISUAL captured " + output.toAbsolutePath());
        });
    }
}
