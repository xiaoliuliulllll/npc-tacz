package com.npctacz.entity.goal;

import com.npctacz.config.NpcConfig;
import com.npctacz.entity.ai.NpcReactionTime;
import com.npctacz.entity.NpcFaction;
import com.npctacz.entity.WastelandNpcEntity;
import com.npctacz.entity.ai.NpcCombatHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 远程交火 Goal：走位 + 点射 + 掩体 + 换位。
 *
 * <p>行为优先级：</p>
 * <ol>
 *   <li>没有视线 → 找侧翼点/绕到能看见的位置；</li>
 *   <li>有视线但距离不合适 → 压上/后撤；</li>
 *   <li>距离合适 → 横向走位（ADAD）+ 点射；</li>
 *   <li>血量低 / 弹匣空 → 找掩体，等补弹；</li>
 *   <li>友军挡在射线上 → 不误伤，换走位。</li>
 * </ol>
 *
 * <p>寻路统一走 {@link NpcNavigator}：目标点移动超过 2 格或路径走完才重算，
 * 降低 PathFinder 压力。</p>
 */
public class NpcRangedCombatGoal extends Goal {

    private static final int THINK_INTERVAL = 6;
    private static final int LOST_NONE = 0;
    private static final int LOST_SUPPRESS = 1;
    private static final int LOST_WAIT = 2;
    private static final int LOST_PUSH = 3;
    private static final int PUSH_DURATION_TICKS = 200;
    private static final int STRAFE_HOLD_MIN = 30;
    private static final int STRAFE_HOLD_MAX = 70;

    private final WastelandNpcEntity npc;

    private int seeTime;
    private int thinkCooldown;
    private int losCacheTicks;
    private boolean losCache;
    private int strafeHoldTicks;
    private float strafeSide = 1.0F;
    private int burstLeft;
    private int burstPause;
    private int flankCooldown;
    private int coverCooldown;
    private int coverHoldTicks;
    private int allyCheckTicks;
    private boolean allyBlocking;
    /** 失去视线后的行为计时。 */
    private int lostSightTicks;
    private int suppressCooldown;
    private int holdTicks;
    private int threatCooldown;
    private int retreatTicks;
    @Nullable
    private Vec3 retreatPoint;
    private int lostAction;
    private int lostActionTicks;
    private int lostTargetId = -1;
    private int pushStrafeTicks;
    private float pushSide = 1.0F;
    /** 计算反应时间用的“首次看见目标”距离（块）。 */
    private double reactionDistance;
    /** 最后一次看见目标的位置：墙后只朝这个点看，不透视目标当前位置。 */
    @Nullable
    private Vec3 lastSeenPos;
    @Nullable
    private Vec3 coverPoint;
    /** 第一次遇到玩家：先拉近/控制到理想距离，不直接开枪。 */
    @Nullable
    private LivingEntity approachTarget;
    private boolean approachMode;

    public NpcRangedCombatGoal(WastelandNpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.npc.isRangedState() || !this.npc.hasGunInHand()) {
            return false;
        }
        LivingEntity target = this.npc.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (target instanceof net.minecraft.world.entity.player.Player
            && !this.npc.isWithinPlayerViewDistance(target)) {
            return false;
        }
        double distanceSqr = this.npc.distanceToSqr(target);
        if (distanceSqr <= 0.25D) {
            return false;
        }
        double engage = this.npc.getEngageRange();
        return distanceSqr <= engage * engage;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.npc.getTarget();
        if (target == null || !target.isAlive() || !this.npc.isRangedState() || !this.npc.hasGunInHand()) {
            return false;
        }
        if (target instanceof net.minecraft.world.entity.player.Player
            && !this.npc.isWithinPlayerViewDistance(target)) {
            return false;
        }
        double engage = this.npc.getEngageRange() * 1.5D;
        return this.npc.distanceToSqr(target) <= engage * engage;
    }

    @Override
    public void start() {
        this.seeTime = 0;
        this.thinkCooldown = 0;
        this.losCacheTicks = 0;
        this.strafeHoldTicks = 0;
        this.burstLeft = NpcConfig.VALUES.burstShots.get();
        this.burstPause = 0;
        this.coverPoint = null;
        this.coverHoldTicks = 0;
        this.allyCheckTicks = 0;
        this.allyBlocking = false;
        this.lostSightTicks = 0;
        this.suppressCooldown = 0;
        this.holdTicks = 0;
        this.threatCooldown = 0;
        this.retreatTicks = 0;
        this.retreatPoint = null;
        this.lostAction = LOST_NONE;
        this.lostActionTicks = 0;
        this.lostTargetId = -1;
        this.pushStrafeTicks = 0;
        this.pushSide = 1.0F;
        this.lastSeenPos = null;
    }

    @Override
    public void stop() {
        this.npc.navigator().stop();
        this.npc.setAiming(false);
    }

    @Override
    public void tick() {
        LivingEntity target = this.npc.getTarget();
        if (target == null) {
            return;
        }
        if (target != this.approachTarget) {
            this.approachTarget = target;
            this.approachMode = target instanceof net.minecraft.world.entity.player.Player;
        }
        boolean canSee = hasLineOfSight(target);
        if (canSee) {
            this.lastSeenPos = target.position();
            this.npc.getLookControl().setLookAt(
                target.getX(), target.getEyeY(), target.getZ(), 30.0F, 30.0F
            );
        } else if (this.lastSeenPos != null) {
            // 墙后不跟瞄实体当前位置，只朝最后可见位置看
            this.npc.getLookControl().setLookAt(
                this.lastSeenPos.x, this.lastSeenPos.y, this.lastSeenPos.z, 30.0F, 30.0F
            );
        }
        if (canSee && this.seeTime == 0) {
            // 首次看见时记录距离：f(x) = t + 0.25 * n，n 按 16 格一档
            this.reactionDistance = Math.sqrt(this.npc.distanceToSqr(target));
            if (target instanceof net.minecraft.world.entity.player.Player) {
                this.npc.combatCallout(switch (this.npc.getFaction()) {
                    case SOLDIER -> "发现目标，准备交战。";
                    case BANDIT -> "有人来了，抄家伙！";
                    case REBEL -> "目标出现。";
                    case MERCENARY -> "接敌。";
                    case BOUNTY_HUNTER -> "找到你了。";
                });
            }
        }
        if (canSee) {
            this.seeTime++;
            this.lostSightTicks = 0;
            this.suppressCooldown = 0;
            this.holdTicks = 0;
        } else {
            this.seeTime = 0;
            this.lostSightTicks++;
        }
        this.updateLostSightAction(target, canSee);
        if (this.suppressCooldown > 0) {
            this.suppressCooldown--;
        }
        if (this.holdTicks > 0) {
            this.holdTicks--;
        }
        // 同步“瞄准中”给客户端：选 gun_hold 动画并抬枪
        double targetDistance = Math.sqrt(this.npc.distanceToSqr(target));
        this.npc.setAiming(canSee && targetDistance <= this.npc.getAttackRange());

        if (this.flankCooldown > 0) {
            this.flankCooldown--;
        }
        if (this.coverCooldown > 0) {
            this.coverCooldown--;
        }
        if (this.coverHoldTicks > 0) {
            this.coverHoldTicks--;
            if (this.coverHoldTicks <= 0) {
                this.coverPoint = null;
            }
        }
        if (this.burstPause > 0) {
            this.burstPause--;
        }

        if (this.retreatTicks > 0) {
            this.retreatTicks--;
        }
        if (this.threatCooldown-- <= 0) {
            this.threatCooldown = 30 + (this.npc.getId() & 15);
            if (this.retreatTicks <= 0) {
                this.assessThreat(target);
            }
        }
        if (this.retreatTicks > 0) {
            this.handleRetreat(target);
        } else if (this.thinkCooldown-- <= 0) {
            this.thinkCooldown = THINK_INTERVAL;
            think(target, canSee);
        }
        maybeShoot(target, canSee);
    }

    // ------------------------------------------------------------------ 走位

    private void think(LivingEntity target, boolean canSee) {
        double distance = Math.sqrt(this.npc.distanceToSqr(target));
        // 苦力怕：太近或已点燃时停止对枪、往后撤，避免被炸死。
        if (target instanceof Creeper creeper
            && (creeper.getSwellDir() > 0 || distance < 5.0D)) {
            Vec3 back = NpcCombatHelper.retreatPoint(this.npc, target, 8.0D);
            if (back != null) {
                this.npc.navigator().moveTo(back, 1.25D);
            } else {
                this.npc.navigator().stop();
            }
            return;
        }
        double preferred = this.npc.getPreferredRange()
            * Math.min(1.6D, NpcConfig.VALUES.aggressionMultiplier.get());

        // 血少 / 弹尽：找掩体
        if (NpcConfig.VALUES.useCover.get() && this.npc.ammo().needsCover() && this.coverCooldown <= 0) {
            this.coverCooldown = 40;
            Vec3 cover = NpcCombatHelper.findCoverPoint(
                this.npc, target, NpcConfig.VALUES.coverSearchRadius.get()
            );
            if (cover != null) {
                this.coverPoint = cover;
                this.coverHoldTicks = 30 + this.npc.getRandom().nextInt(30);
                this.npc.navigator().moveTo(cover, 1.15D);
                return;
            }
        }
        if (this.coverPoint != null) {
            this.npc.navigator().moveTo(this.coverPoint, 1.1D);
            return;
        }

        if (!canSee) {
            this.handleNoSight(target);
            return;
        }

        // 第一次遇到玩家：先控制距离到理想射程，不急着开枪。
        if (this.approachMode && target instanceof net.minecraft.world.entity.player.Player) {
            double tooClose = Math.max(4.0D, preferred * 0.55D);
            if (distance > preferred * 1.10D && distance > tooClose) {
                this.npc.navigator().moveTo(target, 1.05D);
                return;
            }
            this.approachMode = false;
        }
        if (distance > this.npc.getAttackRange() * 1.05D) {
            this.npc.navigator().moveTo(target, 1.05D);
            return;
        }
        double tooClose = Math.max(4.0D, preferred * 0.55D);
        if (target instanceof net.minecraft.world.entity.player.Player
            && distance <= tooClose) {
            // 离玩家太近：不再后撤，直接站定射击
            this.npc.navigator().stop();
            return;
        }
        if (distance < preferred * 0.55D) {
            // NPC 目标：仍然后撤/走位，不站桩
            Vec3 retreat = NpcCombatHelper.retreatPoint(this.npc, target, preferred * 0.8D - distance);
            this.npc.navigator().moveTo(retreat, 1.1D);
            return;
        }
        // 距离合适：优先站定射击；只有太近时才继续移动
        if (com.npctacz.config.NpcGlobalConfig.getBoolean("stand_still_when_shooting", true)) {
            this.npc.navigator().stop();
            return;
        }
        // 距离合适：横向走位
        if (this.strafeHoldTicks-- <= 0) {
            this.strafeHoldTicks = STRAFE_HOLD_MIN
                + this.npc.getRandom().nextInt(STRAFE_HOLD_MAX - STRAFE_HOLD_MIN + 1);
            this.strafeSide = this.npc.getRandom().nextBoolean() ? 1.0F : -1.0F;
        }
        Vec3 side = NpcCombatHelper.sideStep(this.npc, target, this.strafeSide, 2.5D);
        Vec3 spot = NpcCombatHelper.findStandingSpot(
            this.npc.level(), side.x, this.npc.getY(), side.z
        );
        if (spot != null) {
            this.npc.navigator().moveTo(spot, 0.7D);
        } else {
            this.npc.navigator().moveTo(target, 0.9D);
        }
    }

    /**
     * 墙后/失去视线后的三种选择：火力压制、等待/架枪、前压。
     * 不跟随目标真实位置，只使用 lastSeenPos；压制子弹会打在墙上。
     */
    private void updateLostSightAction(LivingEntity target, boolean canSee) {
        if (this.lostAction == LOST_NONE) {
            if (!canSee && this.lastSeenPos != null) {
                this.chooseLostSightAction(target);
            }
            return;
        }
        if (this.lostTargetId != -1 && target.getId() != this.lostTargetId
            && this.lostAction != LOST_PUSH) {
            this.clearLostSightAction();
            return;
        }
        if (canSee && (this.lostAction == LOST_SUPPRESS || this.lostAction == LOST_WAIT)) {
            this.clearLostSightAction();
            return;
        }
        if (--this.lostActionTicks <= 0) {
            if (this.lostAction == LOST_SUPPRESS || this.lostAction == LOST_WAIT) {
                this.lostAction = LOST_PUSH;
                this.lostActionTicks = PUSH_DURATION_TICKS;
                this.pushStrafeTicks = 0;
            } else {
                this.clearLostSightAction();
            }
        }
    }

    private void chooseLostSightAction(LivingEntity target) {
        float roll = this.npc.getRandom().nextFloat();
        if (roll < 0.10F) {
            this.lostAction = LOST_SUPPRESS;
            this.lostActionTicks = 60 + this.npc.getRandom().nextInt(61);
        } else if (roll < 0.70F) {
            this.lostAction = LOST_WAIT;
            this.lostActionTicks = 120 + this.npc.getRandom().nextInt(81);
        } else {
            this.lostAction = LOST_PUSH;
            this.lostActionTicks = PUSH_DURATION_TICKS;
            this.pushStrafeTicks = 0;
        }
        this.lostTargetId = target.getId();
    }

    private void clearLostSightAction() {
        this.lostAction = LOST_NONE;
        this.lostActionTicks = 0;
        this.lostTargetId = -1;
        this.pushStrafeTicks = 0;
    }

    private void handleNoSight(LivingEntity target) {
        Vec3 last = this.lastSeenPos;
        if (last == null) {
            return;
        }
        switch (this.lostAction) {
            case LOST_SUPPRESS -> {
                this.npc.navigator().stop();
                this.npc.getLookControl().setLookAt(last.x, last.y, last.z, 25.0F, 25.0F);
                if (this.npc.ammo().canShoot() && this.suppressCooldown <= 0) {
                    this.suppressCooldown = 8 + this.npc.getRandom().nextInt(10);
                    this.npc.shootAt(target, 4.0F + this.npc.getRandom().nextFloat() * 2.0F);
                }
            }
            case LOST_WAIT -> this.npc.navigator().stop();
            case LOST_PUSH -> this.pushTowardLastSeen(last);
            default -> this.npc.navigator().stop();
        }
    }

    /** 前压时不直线冲脸，低速左右摆动推进。 */
    private void pushTowardLastSeen(Vec3 last) {
        if (this.pushStrafeTicks-- <= 0) {
            this.pushStrafeTicks = 30 + this.npc.getRandom().nextInt(21);
            this.pushSide = this.npc.getRandom().nextBoolean() ? 1.0F : -1.0F;
        }
        Vec3 toLast = last.subtract(this.npc.position());
        Vec3 horizontal = new Vec3(toLast.x, 0.0D, toLast.z);
        if (horizontal.lengthSqr() < 1.0E-4D) {
            this.npc.navigator().stop();
            return;
        }
        Vec3 forward = horizontal.normalize();
        Vec3 side = new Vec3(-forward.z, 0.0D, forward.x)
            .scale(this.pushSide * (1.5D + this.npc.getRandom().nextDouble() * 1.2D));
        Vec3 candidate = this.npc.position().add(side).add(forward.scale(1.8D));
        Vec3 spot = NpcCombatHelper.findStandingSpot(
            this.npc.level(), candidate.x, this.npc.getY(), candidate.z);
        if (spot != null) {
            this.npc.navigator().moveTo(spot, 0.65D);
        } else {
            this.npc.navigator().moveTo(last, 0.65D);
        }
    }

    /** 评估周围威胁；被围/血少/弹药紧张时后撤找掩体并叫支援。 */
    private void assessThreat(LivingEntity target) {
        if (this.npc.level().isClientSide || target == null) {
            return;
        }
        double healthRatio = this.npc.getHealth() / Math.max(1.0F, this.npc.getMaxHealth());
        // 健康且弹药充足时不扫描，减少几十只 NPC 的实体查询压力。
        if (healthRatio > 0.78D && !this.npc.ammo().needsCover()) {
            return;
        }
        AABB box = this.npc.getBoundingBox().inflate(18.0D, 8.0D, 18.0D);
        java.util.List<LivingEntity> nearby = this.npc.level().getEntitiesOfClass(
            LivingEntity.class, box,
            entity -> entity != this.npc && entity.isAlive()
                && (this.npc.isHostileTo(entity)
                    || (entity instanceof WastelandNpcEntity other
                        && other.getFaction() == this.npc.getFaction())));
        if (nearby.isEmpty()) {
            return;
        }
        java.util.List<LivingEntity> enemies = new java.util.ArrayList<>();
        java.util.List<WastelandNpcEntity> allies = new java.util.ArrayList<>();
        for (LivingEntity entity : nearby) {
            if (this.npc.isHostileTo(entity)) {
                enemies.add(entity);
            } else if (entity instanceof WastelandNpcEntity other
                && other.getFaction() == this.npc.getFaction()) {
                allies.add(other);
            }
        }
        if (enemies.isEmpty()) {
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
        boolean outnumbered = enemies.size() >= 3 || enemies.size() > allies.size() + 1;
        boolean shouldRetreat = (healthRatio < 0.45D && outnumbered)
            || healthRatio < 0.28D
            || (outnumbered && healthRatio < 0.70D)
            || (this.npc.ammo().needsCover() && enemies.size() >= 2);
        if (!shouldRetreat) {
            return;
        }
        Vec3 point = this.chooseRetreatPoint(nearest, enemies, allies);
        if (point == null) {
            this.retreatTicks = 0;
            this.retreatPoint = null;
            return;
        }
        this.retreatPoint = point;
        this.retreatTicks = 50 + this.npc.getRandom().nextInt(41);
        this.npc.callBackup(nearest);
    }

    private Vec3 chooseRetreatPoint(LivingEntity nearest, java.util.List<LivingEntity> enemies,
                                    java.util.List<WastelandNpcEntity> allies) {
        Vec3 point = NpcCombatHelper.findCoverPoint(this.npc, nearest, 10);
        if (point == null) {
            point = NpcCombatHelper.retreatPoint(this.npc, nearest, 8.0D);
        }
        // 有队友时优先向队友靠拢，形成交叉火力，不单独跑散。
        if (allies != null && !allies.isEmpty()) {
            WastelandNpcEntity ally = null;
            double bestAlly = Double.MAX_VALUE;
            for (WastelandNpcEntity candidate : allies) {
                double distance = this.npc.distanceToSqr(candidate);
                if (distance < bestAlly && candidate.getHealth() > candidate.getMaxHealth() * 0.5F) {
                    bestAlly = distance;
                    ally = candidate;
                }
            }
            if (ally != null) {
                Vec3 allySpot = NpcCombatHelper.findStandingSpot(
                    this.npc.level(), ally.getX(), ally.getY(), ally.getZ());
                if (allySpot != null && isSaferRetreat(allySpot, enemies)
                    && (point == null || this.npc.position().distanceToSqr(allySpot)
                        < this.npc.position().distanceToSqr(point))) {
                    point = allySpot;
                }
            }
        }
        if (point == null || !isSaferRetreat(point, enemies)) {
            return null;
        }
        if (point.distanceToSqr(this.npc.position()) < 9.0D) {
            return null;
        }
        return point;
    }

    /** 撤退点至少不能比当前位置更靠近敌人。 */
    private boolean isSaferRetreat(Vec3 point, java.util.List<LivingEntity> enemies) {
        double current = Double.MAX_VALUE;
        double candidate = Double.MAX_VALUE;
        for (LivingEntity enemy : enemies) {
            current = Math.min(current, enemy.distanceToSqr(this.npc));
            candidate = Math.min(candidate, enemy.distanceToSqr(point.x, enemy.getY(), point.z));
        }
        return candidate >= current - 2.0D;
    }

    private void handleRetreat(LivingEntity target) {
        this.npc.getLookControl().setLookAt(target.getX(), target.getEyeY(), target.getZ(), 30.0F, 30.0F);
        Vec3 point = this.retreatPoint;
        if (point == null) {
            point = NpcCombatHelper.retreatPoint(this.npc, target, 8.0D);
            this.retreatPoint = point;
        }
        if (point != null) {
            this.npc.navigator().moveTo(point, 1.2D);
        } else {
            this.npc.navigator().stop();
        }
    }

    // ------------------------------------------------------------------ 射击

    private void maybeShoot(LivingEntity target, boolean canSee) {
        int difficulty = this.npc.getNpcDifficulty();
        // 新反应时间系统：难度区间 + 距离档位 + 难度4连杀全局加成
        int reaction = NpcReactionTime.requiredTicks(this.npc, target, this.reactionDistance);
        if (this.lostAction == LOST_PUSH) {
            if (target.getId() == this.lostTargetId) {
                reaction = Math.max(1, reaction / 2);
            } else {
                reaction = Math.max(1, reaction * 3);
            }
        }
        // 对玩家：连续射击直到弹匣清空（忽略点射停顿）
        boolean continuousAgainstPlayer = target instanceof net.minecraft.world.entity.player.Player
            && com.npctacz.config.NpcGlobalConfig.getBoolean(
                "continuous_fire_players", NpcConfig.VALUES.continuousFireOnPlayers.get());
        // 先在便宜的判断上短路，最后才做每发一次的视线 DDA（避免每 tick 都算）
        if (!canSee
            || this.seeTime < reaction
            || (!continuousAgainstPlayer && this.burstPause > 0)
            || !this.npc.canShoot()) {
            return;
        }
        // 切换目标后的转头硬直 + 正面朝向，满足后再开枪。
        if (!this.npc.isTurnReady() || !this.npc.isFacingTarget(target, 12.0F)) {
            return;
        }
        // 每发都重新做一次视线检查：不允许因为 4 tick 的 LOS 缓存而隔墙开火
        if (!NpcCombatHelper.canSee(this.npc, target)) {
            return;
        }
        double distance = Math.sqrt(this.npc.distanceToSqr(target));
        if (target instanceof Creeper creeper
            && (creeper.getSwellDir() > 0 || distance < 5.0D)) {
            return;
        }
        // 对贴脸的弱小生物用近战终结，省子弹，也避免枪口太近。
        if (target instanceof Enemy && !(target instanceof Creeper)
            && distance <= 2.6D && target.getHealth() <= 6.0F
            && this.npc.tickCount % 16 == 0) {
            this.npc.swing(InteractionHand.MAIN_HAND);
            if (this.npc.canAttack(target)) {
                this.npc.doHurtTarget(target);
            }
            return;
        }
        if (distance > this.npc.getAttackRange()) {
            return;
        }
        double preferred = this.npc.getPreferredRange()
            * Math.min(1.6D, NpcConfig.VALUES.aggressionMultiplier.get());
        double tooClose = Math.max(4.0D, preferred * 0.55D);
        if (this.approachMode && target instanceof net.minecraft.world.entity.player.Player) {
            if (distance > preferred * 1.10D && distance > tooClose) {
                // 还没进入理想距离：先移动，不开火
                return;
            }
            this.approachMode = false;
        }
        // 友军挡枪口：不开火，换个走位（每 10 tick 才查询一次，省性能）
        if (this.allyCheckTicks-- <= 0) {
            this.allyCheckTicks = 10;
            this.allyBlocking = NpcCombatHelper.allyInFireLine(
                this.npc, target, Math.min(this.npc.getAttackRange(), 24.0D)
            );
        }
        if (this.allyBlocking) {
            this.strafeHoldTicks = 0;
            return;
        }
        float spread = NpcCombatHelper.movingSpread(this.npc)
            + (float) com.npctacz.config.NpcGlobalConfig.getDouble(
                "spread_degrees", NpcConfig.VALUES.spreadDegrees.get());
        if (this.retreatTicks > 0) {
            // 边后退边射击：散布明显变大，压制可以，精确点杀不行。
            spread = spread * 1.9F + 1.5F;
        }
        this.npc.shootAt(target, spread);
        // 对玩家：连续射击直到弹匣清空；对 NPC/怪物仍保留点射节奏
        this.burstLeft--;
        if (this.burstLeft <= 0) {
            this.burstLeft = NpcConfig.VALUES.burstShots.get();
            if (continuousAgainstPlayer) {
                this.burstPause = 0;
            } else {
                this.burstPause = NpcConfig.VALUES.burstPauseTicks.get()
                    + Math.max(0, 4 - difficulty) * 4
                    + this.npc.getRandom().nextInt(6);
            }
        }
    }

    private boolean hasLineOfSight(LivingEntity target) {
        if (this.losCacheTicks-- <= 0) {
            this.losCache = NpcCombatHelper.canSee(this.npc, target);
            this.losCacheTicks = 8;
        }
        return this.losCache;
    }
}
