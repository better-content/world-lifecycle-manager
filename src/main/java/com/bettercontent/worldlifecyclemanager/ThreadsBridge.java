package com.bettercontent.worldlifecyclemanager;

import net.minecraft.server.level.ServerPlayer;

/** Optional, reflection-only bridge: World Lifecycle Manager remains usable without Better Content Fixes. */
final class ThreadsBridge {
    private ThreadsBridge(){}
    static void emit(ServerPlayer player,String type,String value){
        try{
            var api=Class.forName("com.bettercontent.bettercontentfixes.threads.ThreadSignals");
            api.getMethod("emit",ServerPlayer.class,String.class,String.class).invoke(null,player,type,value);
        }catch(ClassNotFoundException|NoSuchMethodException ignored){
            // Optional integration is absent.
        }catch(ReflectiveOperationException failure){
            PrestigeMod.LOGGER.warn("Threads integration rejected {}:{} for {}",type,value,player.getScoreboardName(),failure);
        }
    }
}
