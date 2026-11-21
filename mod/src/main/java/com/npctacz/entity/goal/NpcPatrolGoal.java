package com.npctacz.entity.goal;

import com.npctacz.entity.NpcFaction;
import com.npctacz.entity.WastelandNpcEntity;
import com.npctacz.entity.ai.NpcCombatHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 巡逻/游荡 Goal。
 *
 * <p>有敌人时不运行；晚上往家/掩体方向靠，白天在出生点附近随机巡逻。
 * 选点会过滤掉水面和悬空位置，避免 NPC 掉进水里或卡在边缘。</p>
 */
public class NpcPatrolGoal extends Goal {

    private static final int WANDER_MIN = 80;
    private static final int WANDER_MAX = 170;

    private final WastelandNpcEntity npc;
    @Nullable
    private Vec3 moveTarget;
    private int cooldown;
    private int nightTicks;

    public NpcPatrolGoal(WastelandNpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.npc.getTarget() != null) {
            return false;
        }
        if (this.npc.tickCount < 40) {
            return false;
        }
        if (this.cooldown-- > 0) {
            return false;
        }
        this.cooldown = WANDER_MIN + this.npc.getRandom().nextInt(WANDER_MAX - WANDER_MIN + 1);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.npc.getTarget() == null && this.moveTarget != null;
    }

    @Override
    public void start() {
        chooseTarget();
    }

    @Override
    public void stop() {
        this.npc.navigator().stop();
        this.moveTarget = null;
    }

    @Override
    public void tick() {
        if (this.moveTarget == null) {
            return;
        }
        if (this.npc.position().distanceToSqr(this.moveTarget) < 2.25D
            || this.npc.getNavigation().isDone()) {
            this.moveTarget = null;
            return;
        }
        this.npc.navigator().moveTo(this.moveTarget, this.npc.isMeleeState() ? 0.55D : 0.7D);
    }

    private void chooseTarget() {
        // 夜晚：往回/往掩体方向走
        if (this.npc.level().isNight()) {
            this.nightTicks++;
            BlockPos home = this.npc.getHomePos();
            if (this.npc.blockPosition().distSqr(home) > 64.0D) {
                this.moveTarget = new Vec3(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D);
                return;
            }
            this.moveTarget = null;
            return;
        }
        this.nightTicks = 0;

        if (isGroupFaction()) {
            WastelandNpcEntity leader = findGroupLeader();
            if (leader != null && leader != this.npc) {
                double leaderDistance = this.npc.distanceToSqr(leader);
                if (leaderDistance > 6.0D * 6.0D) {
                    // Follow the squad leader, but keep a small spacing so the
                    // whole group does not try to stand in one block.
                    double angle = this.npc.getRandom().nextDouble() * Math.PI * 2.0D;
                    this.moveTarget = new Vec3(
                        leader.getX() + Math.cos(angle) * 2.5D,
                        leader.getY(),
                        leader.getZ() + Math.sin(angle) * 2.5D
                    );
                    return;
                }
                for (int attempt = 0; attempt < 8; attempt++) {
                    double angle = this.npc.getRandom().nextDouble() * Math.PI * 2.0D;
                    double distance = 3.0D + this.npc.getRandom().nextDouble() * 4.0D;
                    double x = leader.getX() + Math.cos(angle) * distance;
                    double z = leader.getZ() + Math.sin(angle) * distance;
                    Vec3 spot = NpcCombatHelper.findStandingSpot(this.npc.level(), x, this.npc.getY(), z);
                    if (spot != null) {
                        this.moveTarget = spot;
                        return;
                    }
                }
                this.moveTarget = null;
                return;
            }
        }

        BlockPos home = this.npc.getHomePos();
        if (this.npc.blockPosition().distSqr(home) > 40.0D * 40.0D) {
            this.moveTarget = new Vec3(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D);
            return;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = this.npc.getRandom().nextDouble() * Math.PI * 2.0D;
            double distance = 6.0D + this.npc.getRandom().nextDouble() * 10.0D;
            double x = home.getX() + Math.cos(angle) * distance;
            double z = home.getZ() + Math.sin(angle) * distance;
            Vec3 spot = NpcCombatHelper.findStandingSpot(this.npc.level(), x, this.npc.getY(), z);
            if (spot != null) {
                this.moveTarget = spot;
                return;
            }
        }
        this.moveTarget = null;
    }

    private boolean isGroupFaction() {
        NpcFaction faction = this.npc.getFaction();
        return faction == NpcFaction.SOLDIER || faction == NpcFaction.REBEL;
    }

    /**
     * Squad leader = lowest entity id in the nearby same-faction group. This is
     * stable across ticks and needs no extra saved data or network sync.
     */
    @Nullable
    private WastelandNpcEntity findGroupLeader() {
        AABB box = this.npc.getBoundingBox().inflate(32.0D, 12.0D, 32.0D);
        WastelandNpcEntity leader = this.npc;
        for (WastelandNpcEntity other : this.npc.level().getEntitiesOfClass(
            WastelandNpcEntity.class, box,
            other -> other != this.npc && other.isAlive()
                && other.getFaction() == this.npc.getFaction()
        )) {
            if (other.getId() < leader.getId()) {
                leader = other;
            }
        }
        return leader;
    }
}
