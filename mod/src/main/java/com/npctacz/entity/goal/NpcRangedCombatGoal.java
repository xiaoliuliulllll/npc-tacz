package com.npctacz.entity.goal;

import com.npctacz.config.NpcConfig;
import com.npctacz.entity.ai.NpcReactionTime;
import com.npctacz.entity.WastelandNpcEntity;
import com.npctacz.entity.ai.NpcCombatHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
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
        } else {
            this.seeTime = 0;
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

        if (this.thinkCooldown-- <= 0) {
            this.thinkCooldown = THINK_INTERVAL;
            think(target, canSee);
        }
        maybeShoot(target, canSee);
    }

    // ------------------------------------------------------------------ 走位

    private void think(LivingEntity target, boolean canSee) {
        double distance = Math.sqrt(this.npc.distanceToSqr(target));
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
            // 没有视线：绕侧翼，找不到就直冲目标位置
            if (this.flankCooldown <= 0) {
                this.flankCooldown = 30;
                Vec3 flank = NpcCombatHelper.findFlankPoint(this.npc, target);
                if (flank != null) {
                    this.npc.navigator().moveTo(flank, 1.05D);
                    return;
                }
            }
            this.npc.navigator().moveTo(target, 1.05D);
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

    // ------------------------------------------------------------------ 射击

    private void maybeShoot(LivingEntity target, boolean canSee) {
        int difficulty = this.npc.getNpcDifficulty();
        // 新反应时间系统：难度区间 + 距离档位 + 难度4连杀全局加成
        int reaction = NpcReactionTime.requiredTicks(this.npc, target, this.reactionDistance);
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
            this.losCacheTicks = 4;
        }
        return this.losCache;
    }
}
