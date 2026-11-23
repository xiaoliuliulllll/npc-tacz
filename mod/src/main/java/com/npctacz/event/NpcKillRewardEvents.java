package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * NPC 击杀奖励弹药：
 * <ul>
 *   <li>击杀非玩家生物：5 + 5n 发；</li>
 *   <li>击杀玩家：30 + 5n 发；</li>
 *   <li>n = 当前难度等级（1~4）。</li>
 * </ul>
 * 奖励直接进入击杀者自己的备弹，不产生掉落物。
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcKillRewardEvents {

    private NpcKillRewardEvents() {
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) {
            return;
        }
        Entity source = event.getSource().getEntity();
        if (!(source instanceof WastelandNpcEntity killer)) {
            return;
        }
        if (event.getEntity() == killer) {
            return;
        }
        int difficulty = Math.max(1, Math.min(4, killer.getNpcDifficulty()));
        int amount = event.getEntity() instanceof Player
            ? 30 + 5 * difficulty
            : 5 + 5 * difficulty;
        killer.addKillRewardAmmo(amount);
    }
}
