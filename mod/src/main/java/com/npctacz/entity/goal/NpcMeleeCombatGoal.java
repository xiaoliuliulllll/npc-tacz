package com.npctacz.entity.goal;

import com.npctacz.entity.WastelandNpcEntity;
import com.npctacz.entity.ai.NpcReactionTime;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 近战冲锋 Goal：弹尽或没有枪时贴近目标打近战。
 *
 * <p>移动交给 {@link NpcNavigator}（会自动绕行/跳跃/上梯子）；进入攻击距离后
 * 按固定节奏挥砍并造成伤害。</p>
 */
public class NpcMeleeCombatGoal extends Goal {

    private static final double ATTACK_REACH_SQR = 2.6D * 2.6D;
    private static final int ATTACK_COOLDOWN = 16;

    private final WastelandNpcEntity npc;
    private int attackCooldown;
    private int jumpCooldown;
    /** 发现目标后的反应时间（tick），期间只转身不冲锋/不攻击。 */
    private int reactionTicks;
    @javax.annotation.Nullable
    private LivingEntity reactionTarget;

    public NpcMeleeCombatGoal(WastelandNpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.npc.isMeleeState()) {
            return false;
        }
        LivingEntity target = this.npc.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.npc.getTarget();
        return this.npc.isMeleeState() && target != null && target.isAlive();
    }

    @Override
    public void start() {
        this.attackCooldown = 0;
        this.jumpCooldown = 0;
        this.reactionTarget = null;
        LivingEntity startTarget = this.npc.getTarget();
        if (startTarget != null) {
            this.reactionTarget = startTarget;
            this.reactionTicks = NpcReactionTime.requiredTicks(
                this.npc, startTarget, Math.sqrt(this.npc.distanceToSqr(startTarget)));
        } else {
            this.reactionTicks = 0;
        }
    }

    @Override
    public void stop() {
        this.npc.navigator().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = this.npc.getTarget();
        if (target == null) {
            return;
        }
        this.npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }
        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        }
        // 反应时间：发现目标后先转身观察，再冲锋/攻击
        if (target != this.reactionTarget) {
            this.reactionTarget = target;
            this.reactionTicks = NpcReactionTime.requiredTicks(
                this.npc, target, Math.sqrt(this.npc.distanceToSqr(target)));
        }
        if (this.reactionTicks > 0) {
            this.reactionTicks--;
            this.npc.navigator().stop();
            return;
        }

        if (this.npc.distanceToSqr(target) <= ATTACK_REACH_SQR) {
            this.npc.navigator().stop();
            if (this.attackCooldown <= 0) {
                this.attackCooldown = ATTACK_COOLDOWN;
                // 用 attack 动画表现挥砍（客户端 isFiringAnimation 选 attack 片段）
                this.npc.startFiringAnimation();
                this.npc.swing(InteractionHand.MAIN_HAND);
                if (this.npc.canAttack(target)) {
                    this.npc.doHurtTarget(target);
                }
            }
            return;
        }
        // 冲锋：打不到就一直追
        this.npc.navigator().moveTo(target, 1.15D);
        if (this.jumpCooldown <= 0 && (this.npc.horizontalCollision || this.npc.isInWater())) {
            this.jumpCooldown = 20;
            this.npc.getJumpControl().jump();
        }
    }
}
