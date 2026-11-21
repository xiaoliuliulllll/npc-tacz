package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.config.NpcGlobalConfig;
import com.npctacz.entity.WastelandNpcEntity;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TACZ 枪械伤害兜底 / 诊断。
 *
 * <p>有些整合包（tacz-tweaks、护甲/友伤类模组、客户端服务端枪包不一致）会让
 * TACZ 的子弹伤害事件到了实体这层却被取消，表现就是“枪打出去没伤害”。
 * 这里监听 TACZ 的 {@link EntityHurtByGunEvent.Pre}：</p>
 * <ul>
 *   <li>记录每发命中 NPC 的枪械伤害日志（排查用）；</li>
 *   <li>如果下一 tick NPC 的血量/受伤计数都没变（说明伤害被其它模组吞了），
 *       按 TACZ 给出的 baseAmount 补一次伤害（可用 tacz-npc-modif 的
 *       {@code gun_damage_fallback} 关闭）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GunDamageGuard {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<PendingHit> PENDING = new ArrayList<>();
    private static int gunHitLogs;
    private static int fallbackLogs;

    private GunDamageGuard() {
    }

    private record PendingHit(long dueTick, ServerLevel level, UUID npcId, float healthBefore,
                              long hurtCounterBefore, float amount, @javax.annotation.Nullable UUID attackerId,
                              boolean headshot) {
    }

    @SubscribeEvent
    public static void onGunPre(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        if (!(event.getHurtEntity() instanceof WastelandNpcEntity npc)) {
            return;
        }
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (gunHitLogs < 10) {
            gunHitLogs++;
            LOGGER.info("[npc-tacz] gun hit pre: npc={} gun={} amount={} headshot={} health={}",
                npc.getName().getString(), event.getGunId(), event.getAmount(),
                event.isHeadShot(), npc.getHealth());
        }
        UUID attackerId = event.getAttacker() == null ? null : event.getAttacker().getUUID();
        PENDING.add(new PendingHit(
            level.getGameTime() + 1L,
            level,
            npc.getUUID(),
            npc.getHealth(),
            npc.hurtEventCounter(),
            Math.max(0.0F, event.getAmount()),
            attackerId,
            event.isHeadShot()
        ));
        if (PENDING.size() > 256) {
            PENDING.subList(0, PENDING.size() - 256).clear();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long now = server.overworld() == null ? 0L : server.overworld().getGameTime();
        var iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingHit hit = iterator.next();
            if (now < hit.dueTick()) {
                continue;
            }
            iterator.remove();
            if (!NpcGlobalConfig.getBoolean("gun_damage_fallback", true)) {
                continue;
            }
            Entity entity = hit.level().getEntity(hit.npcId());
            if (!(entity instanceof WastelandNpcEntity npc) || !npc.isAlive()) {
                continue;
            }
            if (npc.getHealth() < hit.healthBefore() - 0.01F
                || npc.hurtEventCounter() != hit.hurtCounterBefore()) {
                // TACZ 的伤害正常到达了，不要重复补刀
                continue;
            }
            LivingEntity attacker = null;
            if (hit.attackerId() != null) {
                Entity attackerEntity = hit.level().getEntity(hit.attackerId());
                if (attackerEntity instanceof LivingEntity living) {
                    attacker = living;
                }
            }
            DamageSource source;
            if (attacker instanceof Player player) {
                source = npc.damageSources().playerAttack(player);
            } else if (attacker != null) {
                source = npc.damageSources().mobAttack(attacker);
            } else {
                source = npc.damageSources().generic();
            }
            float amount = Math.max(1.0F, hit.amount());
            boolean hurt = npc.hurt(source, amount);
            if (fallbackLogs < 10) {
                fallbackLogs++;
                LOGGER.warn("[npc-tacz] gun damage fallback: npc={} gun-amount={} applied={} result={}",
                    npc.getName().getString(), hit.amount(), amount, hurt);
            }
        }
    }
}
