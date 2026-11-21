package com.npctacz.entity.goal;

import com.npctacz.config.NpcConfig;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 破门 Goal。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>木门/栅栏门/木活板门：直接打开，不绕路；</li>
 *   <li>铁门/铁活板门：换铁镐挖开（默认 20~40 tick，掉落铁门），挖完恢复原主手物品；</li>
 *   <li>玻璃板 / 树叶 / 玻璃：挡路时直接打掉（默认允许）；</li>
 *   <li>只在"有目标且被挡住"时触发，平时不会乱拆家。</li>
 * </ul>
 */
public class NpcDoorBreakGoal extends Goal {

    private static final double SCAN_RADIUS = 3.2D;

    private final WastelandNpcEntity npc;
    private int scanCooldown;
    @Nullable
    private BlockPos targetPos;
    private int breakTicks;
    private int breakTotal;
    @Nullable
    private ItemStack savedHand;

    public NpcDoorBreakGoal(WastelandNpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!NpcConfig.VALUES.breakDoors.get() || this.npc.getTarget() == null) {
            return false;
        }
        boolean blocked = this.npc.horizontalCollision || this.npc.navigator().isStuck();
        if (!blocked) {
            return false;
        }
        if (this.scanCooldown-- > 0) {
            return false;
        }
        this.scanCooldown = 8;
        // 优先用寻路器给出的"路径下一步被什么挡住"，比按视线扫更准
        BlockPos pathObstacle = this.npc.navigator().findPathObstacle();
        if (pathObstacle != null
            && isBreachable(this.npc.level().getBlockState(pathObstacle))) {
            this.targetPos = pathObstacle;
        } else {
            this.targetPos = findBlockingBlock();
        }
        if (this.targetPos == null) {
            return false;
        }
        BlockState state = this.npc.level().getBlockState(this.targetPos);
        if (handleOpened(state)) {
            this.targetPos = null;
            this.npc.navigator().clearStuck();
            return false;
        }
        this.breakTotal = breakTimeFor(state);
        this.breakTicks = this.breakTotal;
        if (this.breakTotal <= 0) {
            this.targetPos = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.targetPos == null || this.npc.getTarget() == null) {
            return false;
        }
        BlockState state = this.npc.level().getBlockState(this.targetPos);
        return !state.isAir() && this.npc.distanceToSqr(Vec3.atCenterOf(this.targetPos)) < 16.0D;
    }

    @Override
    public void start() {
        this.savedHand = this.npc.getMainHandItem().copy();
        this.npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
    }

    @Override
    public void stop() {
        if (this.savedHand != null) {
            this.npc.setItemInHand(InteractionHand.MAIN_HAND, this.savedHand);
        }
        this.savedHand = null;
        this.targetPos = null;
        this.breakTicks = 0;
    }

    @Override
    public void tick() {
        if (this.targetPos == null) {
            return;
        }
        BlockState state = this.npc.level().getBlockState(this.targetPos);
        if (state.isAir()) {
            this.targetPos = null;
            return;
        }
        this.npc.getLookControl().setLookAt(
            this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D,
            30.0F, 30.0F
        );
        this.npc.getNavigation().stop();
        if (this.npc.tickCount % 4 == 0) {
            this.npc.swing(InteractionHand.MAIN_HAND);
        }
        if (--this.breakTicks > 0) {
            return;
        }
        breakBlock(this.targetPos, state);
        this.targetPos = null;
        this.npc.navigator().clearStuck();
        this.npc.navigator().requestRepath();
    }

    // ------------------------------------------------------------------ 内部

    /** 找眼前挡路的方块（门/栅栏门/活板门/玻璃/树叶）。 */
    @Nullable
    private BlockPos findBlockingBlock() {
        Vec3 look = this.npc.getLookAngle();
        BlockPos origin = this.npc.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int radius = (int) Math.ceil(SCAN_RADIUS);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = this.npc.level().getBlockState(cursor);
                    if (!isBreachable(state)) {
                        continue;
                    }
                    Vec3 center = Vec3.atCenterOf(cursor);
                    Vec3 delta = center.subtract(this.npc.getEyePosition());
                    double distance = delta.length();
                    if (distance > SCAN_RADIUS) {
                        continue;
                    }
                    if (delta.normalize().dot(look) < 0.2D) {
                        continue;
                    }
                    double score = distance + (dy > 1 ? 1.0D : 0.0D);
                    if (score < bestScore) {
                        bestScore = score;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean isBreachable(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock || block instanceof FenceGateBlock || block instanceof TrapDoorBlock) {
            return true;
        }
        return state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)
            || state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
            || state.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    /** 木质门/栅栏门/活板门直接打开，返回 true 表示已处理。 */
    private boolean handleOpened(BlockState state) {
        Block block = state.getBlock();
        boolean iron = state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR);
        if ((block instanceof DoorBlock || block instanceof TrapDoorBlock) && !iron) {
            this.npc.level().setBlock(this.targetPos,
                state.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
            if (block instanceof DoorBlock) {
                // 双开门：把另一半也打开
                BlockPos other = state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                    ? this.targetPos.above() : this.targetPos.below();
                BlockState otherState = this.npc.level().getBlockState(other);
                if (otherState.getBlock() instanceof DoorBlock) {
                    this.npc.level().setBlock(other,
                        otherState.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
                }
            }
            return true;
        }
        if (block instanceof FenceGateBlock && !iron) {
            this.npc.level().setBlock(this.targetPos,
                state.setValue(BlockStateProperties.OPEN, true), Block.UPDATE_ALL);
            return true;
        }
        return false;
    }

    /** 需要开挖的方块耗时（tick）；0 表示可以直接忽略。 */
    private int breakTimeFor(BlockState state) {
        boolean iron = state.is(Blocks.IRON_DOOR) || state.is(Blocks.IRON_TRAPDOOR);
        if (iron) {
            int min = NpcConfig.VALUES.ironDoorBreakTicksMin.get();
            int max = Math.max(min, NpcConfig.VALUES.ironDoorBreakTicksMax.get());
            return min + this.npc.getRandom().nextInt(max - min + 1);
        }
        if (state.is(net.minecraft.tags.BlockTags.LEAVES)) {
            return 8;
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
            || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) {
            return 12;
        }
        return 0;
    }

    private void breakBlock(BlockPos pos, BlockState state) {
        if (!(this.npc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean ironDoor = state.is(Blocks.IRON_DOOR);
        boolean ironTrapdoor = state.is(Blocks.IRON_TRAPDOOR);
        if (ironDoor) {
            BlockPos lower = state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                ? pos : pos.below();
            serverLevel.removeBlock(lower, false);
            serverLevel.removeBlock(lower.above(), false);
            Block.popResource(serverLevel, lower, new ItemStack(Items.IRON_DOOR));
            return;
        }
        if (ironTrapdoor) {
            serverLevel.removeBlock(pos, false);
            Block.popResource(serverLevel, pos, new ItemStack(Items.IRON_TRAPDOOR));
            return;
        }
        serverLevel.destroyBlock(pos, true, this.npc);
    }
}
