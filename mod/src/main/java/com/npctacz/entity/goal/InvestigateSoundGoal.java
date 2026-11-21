package com.npctacz.entity.goal;

import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 循声搜索：听到枪声后朝声源位置走过去查看。
 *
 * <p>只在"没有活着的攻击目标"时运行；一旦 {@link NpcTargetGoal} 找到敌人，
 * 这个 Goal 就立刻让位给战斗逻辑。走的过程中会不断刷新声音兴趣计时，避免
 * 半路被"附近没玩家就休眠"的省性能逻辑冻住。</p>
 */
public class InvestigateSoundGoal extends Goal {

    /** 到达判定半径（格）。 */
    private static final double ARRIVE_RADIUS = 3.0D;

    /** 寻路重算间隔（tick）。 */
    private static final int REPATH_INTERVAL = 20;

    /** 每次 tick 刷新兴趣时延长的时长（tick）。 */
    private static final int INTEREST_REFRESH_TICKS = 200;

    private final WastelandNpcEntity npc;
    private BlockPos soundPos;
    private int repathCooldown;

    public InvestigateSoundGoal(WastelandNpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.hasCombatTarget()) {
            return false;
        }
        BlockPos pos = this.npc.getSoundInterestPos();
        if (pos == null) {
            return false;
        }
        this.soundPos = pos;
        if (this.npc.blockPosition().distSqr(pos) <= ARRIVE_RADIUS * ARRIVE_RADIUS) {
            this.npc.clearSoundInterest();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.hasCombatTarget()) {
            return false;
        }
        if (this.npc.getSoundInterestPos() == null) {
            return false;
        }
        return this.npc.blockPosition().distSqr(this.soundPos) > ARRIVE_RADIUS * ARRIVE_RADIUS;
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void tick() {
        BlockPos pos = this.npc.getSoundInterestPos();
        if (pos == null) {
            return;
        }
        this.soundPos = pos;
        this.npc.refreshSoundInterest(INTEREST_REFRESH_TICKS);
        this.npc.getLookControl().setLookAt(
            pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 30.0F, 30.0F
        );
        if (this.repathCooldown > 0) {
            this.repathCooldown--;
            return;
        }
        this.repathCooldown = REPATH_INTERVAL;
        this.npc.getNavigation().moveTo(
            pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 1.0D
        );
    }

    @Override
    public void stop() {
        this.npc.getNavigation().stop();
        if (this.soundPos != null
            && this.npc.blockPosition().distSqr(this.soundPos) <= ARRIVE_RADIUS * ARRIVE_RADIUS) {
            this.npc.clearSoundInterest();
        }
    }

    private boolean hasCombatTarget() {
        LivingEntity target = this.npc.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        // 旧的目标选择逻辑可能一直挂着一个很远的目标；只有"能把对方纳入交战范围"
        // 的目标才阻止循声，否则远处目标会把听觉功能整个吞掉。
        double limit = this.npc.getTargetScanRange() * 1.5D;
        return this.npc.distanceToSqr(target) <= limit * limit;
    }
}
