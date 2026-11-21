package com.npctacz.client;

import com.npctacz.ModRegistry;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 按玩家个人可见距离剔除 NPC 渲染。 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class NpcRenderDistanceEvents {

    private NpcRenderDistanceEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onRender(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof WastelandNpcEntity npc)) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int distance = NpcAdminClientSettings.effectiveViewDistance();
        if (distance <= 0) {
            // 剔除关闭：清除别的 Forge 渲染 mod 对这个 NPC 的取消
            if (event.isCanceled()) {
                event.setCanceled(false);
            }
            return;
        }
        if (npc.distanceToSqr(player) > (double) distance * distance) {
            event.setCanceled(true);
        }
    }

    /** 兜底：最低优先级再确认一次，避免其它 Forge 渲染事件处理器把我们的 NPC 取消掉。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void forceVisibleAtLowest(RenderLivingEvent.Pre<?, ?> event) {
        if (!(event.getEntity() instanceof WastelandNpcEntity)) {
            return;
        }
        if (NpcAdminClientSettings.effectiveViewDistance() <= 0 && event.isCanceled()) {
            event.setCanceled(false);
        }
    }
}
