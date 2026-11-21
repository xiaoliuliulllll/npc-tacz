package com.npctacz.event;

import com.npctacz.ModRegistry;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 数据重载时清理本模组的静态性能缓存，避免旧枪包数据残留。 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcCacheEvents {

    private NpcCacheEvents() {
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        com.npctacz.entity.ai.NpcGunRules.clearCaches();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        com.npctacz.entity.ai.NpcGunRules.clearCaches();
    }
}
