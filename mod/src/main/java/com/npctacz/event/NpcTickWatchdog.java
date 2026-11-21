package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.config.NpcGlobalConfig;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AI 看门狗：部分整合包（Mobtimizations / EnhancedAI / 自定义 AI 接管）会跳过
 * 自定义实体的 tick。这里检测“玩家附近的 NPC tickCount 不增长”，记录日志并强制补 tick，
 * 用来确认/兜底“NPC 不会动”的问题。
 */
public final class NpcTickWatchdog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Integer> LAST_TICK = new HashMap<>();
    private static final Map<UUID, Integer> FROZEN = new HashMap<>();
    private static long lastScanTick = -1L;
    private static int protectLogs;
    private static int leaveLogs;
    static { LOGGER.info("[npc-tacz] watchdog class loaded"); }

    private NpcTickWatchdog() {
    }

    @SubscribeEvent
    public static void onEntityLeave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof WastelandNpcEntity && leaveLogs < 5) {
            leaveLogs++;
            LOGGER.info("[npc-tacz] NPC left level (normal death/despawn)");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void protectNpcJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof WastelandNpcEntity npc)) {
            return;
        }
        boolean canceled = event.isCanceled();
        boolean removed = npc.isRemoved();
        if (canceled) {
            event.setCanceled(false);
        }
        if (removed) {
            try {
                java.lang.reflect.Field field = net.minecraft.world.entity.Entity.class.getDeclaredField("removalReason");
                field.setAccessible(true);
                field.set(npc, null);
            } catch (Throwable throwable) {
                LOGGER.warn("[npc-tacz] failed to clear NPC removal reason", throwable);
            }
        }
        if ((canceled || removed) && protectLogs < 6) {
            protectLogs++;
            LOGGER.warn("[npc-tacz] protected NPC join: canceled={} removed={} pos={} (another mod tried to remove it)",
                canceled, removed, npc.blockPosition());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoinHigh(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof WastelandNpcEntity npc) {
            // 先占住事件，让其它模组的 EntityJoinLevelEvent 处理器（多数会检查 isCanceled 直接 return）
            // 不在 HIGHEST 之后误删我们的 NPC；LOWEST 再放行。
            if (!event.isCanceled()) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null || server.getPlayerList().getPlayerCount() == 0) {
            return;
        }
        if (!NpcGlobalConfig.getBoolean("ai_watchdog", true)) {
            return;
        }
        long gameTime = server.overworld().getGameTime();
        if (lastScanTick < 0L) {
            lastScanTick = gameTime;
            return;
        }
        if (gameTime - lastScanTick < 20L) {
            return;
        }
        lastScanTick = gameTime;

        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            AABB box = player.getBoundingBox().inflate(64.0D);
            var nearby = level.getEntitiesOfClass(WastelandNpcEntity.class, box);
            for (WastelandNpcEntity npc : nearby) {
                if (!npc.isAlive() || !seen.add(npc.getUUID())) {
                    continue;
                }
                UUID id = npc.getUUID();
                int tick = npc.tickCount;
                Integer last = LAST_TICK.put(id, tick);
                boolean advanced = last != null && tick != last;
                int frozen = advanced ? 0 : FROZEN.merge(id, 1, Integer::sum);
                if (frozen >= 2) {
                    LOGGER.warn("[npc-tacz] watchdog frozen: npc={} faction={} tickCount={} pos={} hand={} target={} NoAI={} weak={} dist={}",
                        npc.getName().getString(), npc.getFaction(), tick, npc.blockPosition(),
                        npc.getMainHandItem().getItem(), npc.getTarget() == null ? "none"
                            : npc.getTarget().getName().getString(), npc.isNoAi(), npc.isWeakRunning(),
                        String.format("%.1f", Math.sqrt(npc.distanceToSqr(player))));
                    // 强制补一次 tick（正常情况下 tick 会推进，不会走到这里）
                    try {
                        npc.tick();
                    } catch (Throwable throwable) {
                        LOGGER.warn("[npc-tacz] watchdog forced tick failed", throwable);
                    }
                    LOGGER.warn("[npc-tacz] watchdog forced tick result: tick={} alive={}", npc.tickCount, npc.isAlive());
                    FROZEN.put(id, 0);
                }
            }
        }
    }
}
