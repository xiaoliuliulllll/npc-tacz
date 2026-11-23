package com.npctacz.entity.goal;

import com.npctacz.entity.WastelandNpcEntity;
import com.npctacz.entity.ai.NpcReactionTime;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
    private int threatCooldown;
    private int retreatTicks;
    @javax.annotation.Nullable
    private Vec3 retreatPoint;

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
        this.threatCooldown = 0;
        this.retreatTicks = 0;
        this.retreatPoint = null;
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
        if (this.retreatTicks > 0) {
            this.retreatTicks--;
            this.handleRetreat(target);
            return;
        }
        if (this.threatCooldown-- <= 0) {
            this.threatCooldown = 20 + (this.npc.getId() & 15);
            this.assessThreat(target);
            if (this.retreatTicks > 0) {
                this.handleRetreat(target);
                return;
            }
        }
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

    /** 被多人围住/低血时后撤找掩体，避免一个人冲进人海送死。 */
    private void assessThreat(LivingEntity target) {
        if (this.npc.level().isClientSide || target == null) {
            return;
        }
        AABB box = this.npc.getBoundingBox().inflate(16.0D, 8.0D, 16.0D);
        java.util.List<LivingEntity> enemies = this.npc.level().getEntitiesOfClass(
            LivingEntity.class, box,
            entity -> entity != this.npc && entity.isAlive() && this.npc.isHostileTo(entity));
        if (enemies.size() < 2) {
            return;
        }
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (LivingEntity enemy : enemies) {
            double distance = this.npc.distanceToSqr(enemy);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = enemy;
            }
        }
        if (nearest == null) {
            return;
        }
        double healthRatio = this.npc.getHealth() / Math.max(1.0F, this.npc.getMaxHealth());
        if (healthRatio > 0.45D) {
            return;
        }
        Vec3 point = com.npctacz.entity.ai.NpcCombatHelper.findCoverPoint(this.npc, nearest, 8);
        if (point == null) {
            point = com.npctacz.entity.ai.NpcCombatHelper.retreatPoint(this.npc, nearest, 7.0D);
        }
        this.retreatPoint = point;
        this.retreatTicks = 40 + this.npc.getRandom().nextInt(31);
        this.npc.callBackup(nearest);
    }

    private void handleRetreat(LivingEntity target) {
        this.npc.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ(), 30.0F, 30.0F);
        Vec3 point = this.retreatPoint;
        if (point == null) {
            point = com.npctacz.entity.ai.NpcCombatHelper.retreatPoint(this.npc, target, 7.0D);
            this.retreatPoint = point;
        }
        if (point != null) {
            this.npc.navigator().moveTo(point, 1.2D);
        } else {
            this.npc.navigator().stop();
        }
    }
}
