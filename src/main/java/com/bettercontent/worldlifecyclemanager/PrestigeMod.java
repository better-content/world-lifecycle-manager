package com.bettercontent.worldlifecyclemanager;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(PrestigeMod.MOD_ID)
public final class PrestigeMod {
    public static final String MOD_ID = "world_lifecycle_manager";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PrestigeMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        PrestigeRegistry.BLOCKS.register(modBus);
        PrestigeRegistry.ITEMS.register(modBus);
        PrestigeRegistry.BLOCK_ENTITIES.register(modBus);
        PrestigeRegistry.MENUS.register(modBus);
        PrestigeRegistry.RECIPE_TYPES.register(modBus);
        PrestigeRegistry.RECIPE_SERIALIZERS.register(modBus);
        PrestigeNetwork.register();
        SchematicannonSubstitutionNetwork.register();
        modBus.addListener(this::onCreativeTab);
        modBus.addListener(this::onRegisterGameTests);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            modBus.addListener(PrestigeClient::setup);
            MinecraftForge.EVENT_BUS.register(SchematicannonSubstitutionClient.class);
        });
        MinecraftForge.EVENT_BUS.register(PrestigeCoordinator.class);
    }

    private void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
            event.accept(PrestigeRegistry.WORLD_CONDENSER_HULL_ITEM);
            event.accept(PrestigeRegistry.WORLD_CONDENSER_INTERFACE_ITEM);
            event.accept(PrestigeRegistry.SCHEMATIC_MINT_ITEM);
        }
    }

    private void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(WorldCondenserGameTests.class);
    }
}
