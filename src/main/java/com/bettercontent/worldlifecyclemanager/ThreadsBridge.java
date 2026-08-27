package com.bettercontent.worldlifecyclemanager;

import net.minecraft.server.level.ServerPlayer;

/** Optional, reflection-only bridge: World Lifecycle Manager remains usable without Threads. */
final class ThreadsBridge {
    private ThreadsBridge(){}
    static void emit(ServerPlayer player,String type,String value){
        emit(player,type,value,null);
    }
    static void emit(ServerPlayer player,String type,String value,String correlation){
        try{
            var api=Class.forName("com.bettercontent.threads.api.ThreadSignals");
            api.getMethod("emit",ServerPlayer.class,String.class,String.class,String.class).invoke(null,player,type,value,correlation);
        }catch(ClassNotFoundException|NoSuchMethodException ignored){
            // Optional integration is absent.
        }catch(ReflectiveOperationException failure){
            PrestigeMod.LOGGER.warn("Threads integration rejected {}:{} for {}",type,value,player.getScoreboardName(),failure);
        }
    }
}
