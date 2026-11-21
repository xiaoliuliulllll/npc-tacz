package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.entity.BountyHunterNpc;
import com.npctacz.entity.ai.NpcReactionTime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Tracks player deaths for each bounty hunter's lifetime.
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BountyEvents {
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide) {
            return;
        }
        for (BountyHunterNpc hunter : player.level().getEntitiesOfClass(
            BountyHunterNpc.class, player.getBoundingBox().inflate(256.0D))) {
            hunter.onTrackedPlayerDeath(player);
        }
        // 难度 4：玩家死亡后连杀反应时间加成清零恢复
        if (player instanceof ServerPlayer serverPlayer) {
            NpcReactionTime.resetOnPlayerDeath(serverPlayer);
        }
    }

    private BountyEvents() {
    }
}
