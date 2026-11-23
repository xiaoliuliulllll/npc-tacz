package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.data.DeathLootBarrelData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 自动死亡桶：
 * <ul>
 *   <li>每 20 tick 清理过期的桶；</li>
 *   <li>玩家破坏桶后取消追踪；</li>
 *   <li>玩家在追踪坐标重新放桶时也取消追踪。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DeathLootBarrelEvents {

    private DeathLootBarrelEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        ServerLevel overworld = event.getServer().overworld();
        if (overworld != null) {
            DeathLootBarrelData.get(overworld).tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            DeathLootBarrelData.get(level).untrack(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player && event.getLevel() instanceof ServerLevel level) {
            DeathLootBarrelData.get(level).untrack(level, event.getPos());
        }
    }
}
