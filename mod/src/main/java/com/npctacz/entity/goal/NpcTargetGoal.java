package com.npctacz.entity.goal;

import com.npctacz.config.NpcConfig;
import com.npctacz.entity.NpcFaction;
import com.npctacz.entity.WastelandNpcEntity;
import com.npctacz.entity.ai.NpcCombatHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 索敌 Goal。
 *
 * <p>用 {@link NpcCombatHelper#scanForTarget} 给候选目标打分：距离、视线、
 * 当前目标粘性、刚刚攻击过自己的敌人优先。目标只在"失效/脱离范围"时才释放，
 * 避免两个目标来回抢导致 NPC 原地摇头（这也是原版 HurtByTarget 常见的抖动源）。</p>
 */
public class NpcTargetGoal extends TargetGoal {

    private final WastelandNpcEntity npc;
    private int scanCooldown;
    private int groupScanCooldown;
    @Nullable
    private LivingEntity cachedGroupTarget;

    public NpcTargetGoal(WastelandNpcEntity npc) {
        super(npc, false);
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.TARGET));
        // 错开不同 NPC 的第一次索敌，避免几十只同时扫描造成单 tick 卡顿。
        int interval = Math.max(1, NpcConfig.VALUES.targetScanIntervalTicks.get());
        this.scanCooldown = Math.floorMod(npc.getId() * 7, interval);
    }

    @Override
    public boolean canUse() {
        if (this.groupScanCooldown > 0) {
            this.groupScanCooldown--;
        }
        if (this.cachedGroupTarget != null
            && (!this.cachedGroupTarget.isAlive() || !this.npc.isHostileTo(this.cachedGroupTarget))) {
            this.cachedGroupTarget = null;
        }
        // 先手反击目标：100% 优先，不参与扫描换目标
        LivingEntity forced = this.npc.getForcedTarget();
        if (forced != null) {
            if (this.npc.getTarget() != forced) {
                this.targetMob = forced;
                this.npc.setTarget(forced);
            }
            // 继续走视线/距离校验，避免被反击目标重新变成永久穿墙锁定。
            return keepCurrentTarget();
        }
        if (this.npc.isWeakRunning() && this.npc.getTarget() == null) {
            return false;
        }
        if (keepCurrentTarget()) {
            // 当前目标有效时仍定期检查是否有更高优先级目标（攻击者 > 敌对生物 > 玩家 > 敌对NPC）
            LivingEntity current = this.npc.getTarget();
            if (current != null && this.npc.tickCount % 40 == 0) {
                LivingEntity better = NpcCombatHelper.scanForTarget(
                    this.npc, this.npc.getTargetScanRange());
                boolean currentArmed = current instanceof net.minecraft.world.entity.player.Player currentPlayer
                    && NpcCombatHelper.isArmedPlayer(currentPlayer);
                boolean betterArmed = better instanceof net.minecraft.world.entity.player.Player betterPlayer
                    && NpcCombatHelper.isArmedPlayer(betterPlayer);
                boolean betterChoice = better != null && this.npc.isHostileTo(better)
                    && (betterArmed && !currentArmed
                        || (betterArmed == currentArmed
                            && this.npc.distanceToSqr(better) + 16.0D < this.npc.distanceToSqr(current)));
                if (betterChoice) {
                    this.npc.setTarget(better);
                    return true;
                }
            }
            return false;
        }
        if (this.scanCooldown-- > 0) {
            return false;
        }
        this.scanCooldown = NpcConfig.VALUES.targetScanIntervalTicks.get();
        LivingEntity found = NpcCombatHelper.scanForTarget(this.npc, this.npc.getTargetScanRange());
        boolean foundArmedPlayer = found instanceof net.minecraft.world.entity.player.Player foundPlayer
            && NpcCombatHelper.isArmedPlayer(foundPlayer);
        LivingEntity groupTarget = groupTarget();
        if (!foundArmedPlayer && groupTarget != null && this.npc.isHostileTo(groupTarget)
            && (found == null || this.npc.distanceToSqr(groupTarget) < this.npc.distanceToSqr(found))) {
            found = groupTarget;
        }
        if (found == null) {
            return false;
        }
        this.targetMob = found;
        this.npc.setTarget(found);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return keepCurrentTarget();
    }

    /** 当前目标还有效就继续用，不重新扫。 */
    private boolean keepCurrentTarget() {
        LivingEntity forced = this.npc.getForcedTarget();
        if (forced != null) {
            // 正在反击时攻击者最高优先；武装玩家优先只用于普通索敌。
            if (this.npc.getTarget() != forced) {
                this.npc.setTarget(forced);
                return true;
            }
        }
        LivingEntity target = this.npc.getTarget();
        boolean forcedTarget = forced != null && target == forced;
        if (target == null || !target.isAlive()
            || (!forcedTarget && !this.npc.isHostileTo(target))) {
            if (target != null) {
                this.npc.setTarget(null);
            }
            return false;
        }
        double limit = this.npc.getTargetScanRange() * 2.5D;
        if (this.npc.distanceToSqr(target) > limit * limit) {
            this.npc.setTarget(null);
            return false;
        }
        if (this.npc.tickCount % 20 == 0) {
            boolean visible = NpcCombatHelper.canSee(this.npc, target);
            this.npc.noteTargetVisibility(visible, 20);
            // 失去视线后保留记忆，给火力压制/等待/前压留出时间；
            // 最长 21 秒仍无视线才真正丢目标（等待10秒+前压最长10秒）。
            if (!visible && this.npc.getTargetLosLostTicks() >= 420) {
                if (forcedTarget) {
                    this.npc.clearRetaliation();
                }
                this.npc.setTarget(null);
                return false;
            }
        }
        return true;
    }

    @Nullable
    private LivingEntity groupTarget() {
        if (this.groupScanCooldown > 0) {
            return this.cachedGroupTarget;
        }
        this.groupScanCooldown = 40 + (this.npc.getId() & 15);
        NpcFaction faction = this.npc.getFaction();
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (WastelandNpcEntity ally : this.npc.level().getEntitiesOfClass(
            WastelandNpcEntity.class,
            this.npc.getBoundingBox().inflate(24.0D, 12.0D, 24.0D),
            other -> other != this.npc && other.isAlive() && other.getFaction() == faction
        )) {
            LivingEntity allyTarget = ally.getTarget();
            if (allyTarget == null || !allyTarget.isAlive() || !this.npc.isHostileTo(allyTarget)) {
                continue;
            }
            double distance = this.npc.distanceToSqr(allyTarget);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = allyTarget;
            }
        }
        this.cachedGroupTarget = best;
        return best;
    }

    @Nullable
    public LivingEntity target() {
        return this.npc.getTarget();
    }
}
