package com.bettercontent.prestige;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class PrestigeClient {
    private PrestigeClient() {}
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(PrestigeRegistry.WORLD_CONDENSER_MENU.get(), WorldCondenserScreen::new));
    }
}
