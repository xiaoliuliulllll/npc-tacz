package com.npctacz.entity.ai;

import com.npctacz.config.NpcConfig;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * NPC 的移动/寻路辅助。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>统一重算节奏：目标移动超过 2 格或路径走完才重算（距离分档 8/12/20 tick），
 *       减少 PathFinder 调用；</li>
 *   <li>卡住检测与脱困：跳跃 → 侧向小绕行 → 逐步加大绕行距离 → 换方向；</li>
 *   <li>落水脱困：往最近可站立的地面走；</li>
 *   <li>梯子/藤蔓：目标在上方时自动上爬；</li>
 *   <li>路径障碍：给破门 Goal 提供"下一个路径节点被什么挡住"的位置。</li>
 * </ul>
 */
public final class NpcNavigator {

    private final WastelandNpcEntity npc;

    private int repathCooldown;
    private int stuckTicks;
    private int jumpCooldown;
    private int waterTicks;
    private int detourTicks;
    private int detourCount;
    private BlockPos lastPos = BlockPos.ZERO;
    @Nullable
    private Vec3 lastMoveTarget;

    public NpcNavigator(WastelandNpcEntity npc) {
        this.npc = npc;
        this.lastPos = npc.blockPosition();
        // Stagger initial path cooldowns so dozens of NPCs do not repath on the
        // same tick after a world load or a target change.
        this.repathCooldown = 1 + (npc.getId() & 7);
    }

    public void tick() {
        if (this.npc.isMovementLocked()) {
            this.stop();
            return;
        }
        if (this.npc.level().isClientSide) {
            return;
        }
        if (this.repathCooldown > 0) {
            this.repathCooldown--;
        }
        if (this.jumpCooldown > 0) {
            this.jumpCooldown--;
        }
        if (this.detourTicks > 0) {
            this.detourTicks--;
        }

        LivingEntity target = this.npc.getTarget();
        // 梯子/藤蔓辅助：目标在上方时主动往上爬
        if (this.npc.onClimbable() && target != null && target.getY() > this.npc.getY() + 0.5D) {
            Vec3 motion = this.npc.getDeltaMovement();
            this.npc.setDeltaMovement(motion.x, Math.max(motion.y, 0.16D), motion.z);
        }

        // 掉进水里：往岸边走
        if (this.npc.isInWater() && !this.npc.canBreatheUnderwater()) {
            this.waterTicks++;
            if (this.waterTicks % 15 == 0) {
                escapeWater(target);
            }
        } else {
            this.waterTicks = 0;
        }

        // Stagger the expensive stuck/path audit by entity id: 10 ticks is
        // still responsive, but the whole population no longer lands on one tick.
        if ((this.npc.tickCount + this.npc.getId()) % 10 != 0) {
            return;
        }
        if (!this.npc.getNavigation().isInProgress() || this.detourTicks > 0) {
            this.stuckTicks = Math.max(0, this.stuckTicks - 10);
            this.detourCount = Math.max(0, this.detourCount - 1);
            this.lastPos = this.npc.blockPosition();
            return;
        }
        double moved = Math.sqrt(this.lastPos.distSqr(this.npc.blockPosition()));
        this.lastPos = this.npc.blockPosition();
        if (moved < 0.4D) {
            this.stuckTicks += 10;
        } else {
            this.stuckTicks = Math.max(0, this.stuckTicks - 20);
            this.detourCount = 0;
        }
        // 路径下一步是水：提前绕开
        if (nextPathNodeIsWater()) {
            this.stuckTicks += 10;
            if (this.repathCooldown <= 0) {
                this.requestRepath();
                this.repathCooldown = 20;
            }
        }
        handleStuck(target);
    }

    private void handleStuck(@Nullable LivingEntity target) {
        if (this.stuckTicks < 20) {
            return;
        }
        if (NpcConfig.VALUES.pathStuckJump.get() && this.jumpCooldown <= 0) {
            this.jumpCooldown = 12;
            this.npc.getJumpControl().jump();
        }
        if (target == null) {
            this.stuckTicks = 0;
            return;
        }
        if (this.stuckTicks < NpcConfig.VALUES.stuckDetourTicks.get() || this.detourTicks > 0) {
            return;
        }
        // 绕行距离逐步加大，尽量绕开墙角/栅栏
        this.detourCount = Math.min(this.detourCount + 1, 3);
        double detourDistance = 2.5D + this.detourCount * 1.8D;
        double side = this.npc.getRandom().nextBoolean() ? 1.0D : -1.0D;
        Vec3 sidePoint = NpcCombatHelper.sideStep(this.npc, target, side, detourDistance);
        Vec3 spot = NpcCombatHelper.findStandingSpot(
            this.npc.level(), sidePoint.x, this.npc.getY(), sidePoint.z
        );
        if (spot == null) {
            // 侧面没有地面：往目标方向偏一点再试
            Vec3 toward = target.position().subtract(this.npc.position()).normalize()
                .scale(detourDistance)
                .add(this.npc.position());
            spot = NpcCombatHelper.findStandingSpot(
                this.npc.level(), toward.x, this.npc.getY(), toward.z
            );
        }
        if (spot != null) {
            this.npc.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.05D);
            this.detourTicks = 35 + this.detourCount * 10;
            this.stuckTicks = 10;
        } else {
            this.stuckTicks = 0;
        }
    }

    /** 往最近的岸上走（优先目标方向那半边）。 */
    private void escapeWater(@Nullable LivingEntity target) {
        double preferredAngle = 0.0D;
        if (target != null) {
            preferredAngle = Math.atan2(target.getZ() - this.npc.getZ(), target.getX() - this.npc.getX());
        }
        for (int radius = 4; radius <= 16; radius += 4) {
            for (int i = 0; i < 8; i++) {
                double angle = preferredAngle + i * (Math.PI / 4.0D) * (i % 2 == 0 ? 1 : -1);
                double x = this.npc.getX() + Math.cos(angle) * radius;
                double z = this.npc.getZ() + Math.sin(angle) * radius;
                Vec3 spot = NpcCombatHelper.findStandingSpot(this.npc.level(), x, this.npc.getY(), z);
                if (spot != null) {
                    this.npc.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.1D);
                    return;
                }
            }
        }
    }

    /** 当前路径的下一个节点是否落在水里。 */
    private boolean nextPathNodeIsWater() {
        Path path = this.npc.getNavigation().getPath();
        if (path == null) {
            return false;
        }
        int next = path.getNextNodeIndex();
        if (next < 0 || next >= path.getNodeCount()) {
            return false;
        }
        Node node = path.getNode(next);
        BlockPos pos = node.asBlockPos();
        return !this.npc.level().getBlockState(pos).getFluidState().isEmpty()
            || !this.npc.level().getBlockState(pos.above()).getFluidState().isEmpty();
    }

    /**
     * 找到"下一个路径节点被什么方块挡住"的位置，供破门/清障 Goal 使用。
     *
     * @return 挡路方块的坐标；没有则 null
     */
    @Nullable
    public BlockPos findPathObstacle() {
        Path path = this.npc.getNavigation().getPath();
        if (path == null) {
            return null;
        }
        int next = path.getNextNodeIndex();
        int count = path.getNodeCount();
        if (next < 0) {
            return null;
        }
        for (int i = next; i < Math.min(count, next + 3); i++) {
            Node node = path.getNode(i);
            BlockPos pos = node.asBlockPos();
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos check = pos.offset(0, dy, 0);
                BlockState state = this.npc.level().getBlockState(check);
                if (state.isAir()) {
                    continue;
                }
                if (!state.getCollisionShape(this.npc.level(), check).isEmpty()) {
                    return check;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 移动接口

    /** 每 tick 全局重算路径预算：避免几十只 NPC 在同一 tick 集体跑 PathFinder。 */
    private static long repathBudgetTick = Long.MIN_VALUE;
    private static int repathBudgetLeft;
    private static final int REPATH_BUDGET_PER_TICK = 16;

    private static boolean takeRepathBudget(WastelandNpcEntity npc) {
        long tick = npc.level().getGameTime();
        if (tick != repathBudgetTick) {
            repathBudgetTick = tick;
            repathBudgetLeft = REPATH_BUDGET_PER_TICK;
        }
        if (repathBudgetLeft <= 0) {
            return false;
        }
        repathBudgetLeft--;
        return true;
    }

    public boolean moveTo(LivingEntity target, double speed) {
        if (this.npc.isMovementLocked()) {
            this.stop();
            return false;
        }
        if (this.detourTicks > 0) {
            return true;
        }
        // Long chases are split into local 48-block legs. This keeps the
        // PathFinder search bounded even when a hunter is 200+ blocks away.
        if (this.npc.distanceToSqr(target) > 48.0D * 48.0D) {
            return this.moveTo(limitTarget(target.position()), speed);
        }
        if (this.repathCooldown > 0 && this.npc.getNavigation().isInProgress()) {
            return true;
        }
        if (!takeRepathBudget(this.npc)) {
            return false;
        }
        boolean moved = this.npc.getNavigation().moveTo(target, speed);
        this.repathCooldown = repathInterval(target.position());
        this.lastMoveTarget = target.position();
        return moved;
    }

    public boolean moveTo(Vec3 pos, double speed) {
        if (this.npc.isMovementLocked()) {
            this.stop();
            return false;
        }
        if (this.detourTicks > 0) {
            return true;
        }
        Vec3 target = limitTarget(pos);
        if (this.lastMoveTarget != null
            && this.lastMoveTarget.distanceToSqr(target) < 4.0D
            && this.npc.getNavigation().isInProgress()) {
            return true;
        }
        if (this.repathCooldown > 0 && this.npc.getNavigation().isInProgress()) {
            return true;
        }
        if (!takeRepathBudget(this.npc)) {
            return false;
        }
        boolean moved = this.npc.getNavigation().moveTo(target.x, target.y, target.z, speed);
        this.lastMoveTarget = target;
        this.repathCooldown = repathInterval(target);
        return moved;
    }

    /** Bound a long-range destination to a local leg (48 blocks). */
    private Vec3 limitTarget(Vec3 target) {
        Vec3 position = this.npc.position();
        double distance = position.distanceTo(target);
        if (distance <= 48.0D) {
            return target;
        }
        Vec3 direction = target.subtract(position).normalize();
        return position.add(direction.scale(48.0D));
    }

    private int repathInterval(Vec3 target) {
        double distance = Math.sqrt(this.npc.position().distanceToSqr(target));
        if (distance < 8.0D) {
            return 8;
        }
        if (distance < 24.0D) {
            return 12;
        }
        if (distance > 48.0D) {
            return 30;
        }
        return 20;
    }

    public void stop() {
        this.npc.getNavigation().stop();
        this.repathCooldown = 0;
        this.lastMoveTarget = null;
    }

    public boolean isStuck() {
        return this.stuckTicks >= 20;
    }

    public void requestRepath() {
        this.repathCooldown = 0;
        this.npc.getNavigation().stop();
        this.lastMoveTarget = null;
    }

    /** 清空卡住计数（破门成功后调用，避免继续绕路）。 */
    public void clearStuck() {
        this.stuckTicks = 0;
        this.detourCount = 0;
        this.detourTicks = 0;
    }
}
