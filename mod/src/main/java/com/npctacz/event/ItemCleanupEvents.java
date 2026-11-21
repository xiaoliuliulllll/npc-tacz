package com.npctacz.event;

import com.npctacz.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Periodic ground-item cleanup (the old KubeJS cleanup, now built into the mod).
 *
 * <p>Every 36 minutes it removes dropped items and experience orbs in every
 * dimension. Thirty seconds before the sweep it broadcasts a bold blue warning
 * in chat. Player inventories, blocks and living entities are untouched.</p>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ItemCleanupEvents {

    /** 36 minutes, matching the old cleanup_items.js. */
    private static final int CLEAN_INTERVAL_TICKS = 20 * 60 * 36;
    /** 30 seconds warning. */
    private static final int WARN_TICKS = 20 * 30;

    private static int tickCounter;
    private static boolean warned;

    private ItemCleanupEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        tickCounter++;

        if (!warned && tickCounter >= CLEAN_INTERVAL_TICKS - WARN_TICKS) {
            warned = true;
            Component message = Component.literal("30 秒后清理掉落物和经验球")
                .withStyle(ChatFormatting.BOLD, ChatFormatting.BLUE);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(message);
            }
        }

        if (tickCounter < CLEAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        warned = false;
        cleanup(server);
    }

    private static void cleanup(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
                    entity.discard();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            com.npctacz.entity.WastelandNpcEntity.LOGGER.info("[npc-tacz] periodic cleanup removed {} ground item/orb entities", removed);
        }
    }
}
