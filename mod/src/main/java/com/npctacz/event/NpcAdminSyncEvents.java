package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.network.AdminSettingsMessage;
import com.npctacz.network.NpcNetwork;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 玩家登录时同步自己的可见距离和管理员设置快照。 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcAdminSyncEvents {

    private NpcAdminSyncEvents() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            NpcNetwork.sendAdminSettings(player, AdminSettingsMessage.snapshot(player));
        }
    }
}
