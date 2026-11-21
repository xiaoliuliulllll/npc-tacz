package com.npctacz.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * NPC 的视线判定（能看穿玻璃、树叶、栏杆、水等）。
 *
 * <p>原版 {@code Mob#hasLineOfSight} 用碰撞形状做射线检测，玻璃/树叶会挡住视线。
 * 这里自己沿射线做 3D DDA 体素步进，逐格检查方块状态：</p>
 *
 * <ul>
 *   <li>空气 / 水 / 树叶 / 玻璃(含染色、遮光) / 玻璃板 / 铁栏杆 → 可以看穿；</li>
 *   <li>没有碰撞体积的方块（草、花、火把、铁轨、按钮…） → 可以看穿；</li>
 *   <li>其余实心方块 → 挡视线。</li>
 * </ul>
 *
 * <p>DDA 只访问射线真正穿过的格子，比 0.5 格采样的循环少很多次
 * {@code getBlockState}，同屏 NPC 多时对帧率更友好。</p>
 */
public final class NpcSight {

    /** 超过这个距离就不再逐格检查（视野 192 格已经超过所有枪械射程）。 */
    private static double maxSightDistance() {
        return Math.max(16.0D, com.npctacz.config.NpcGlobalConfig.getDouble("vision_max_distance", 192.0D));
    }

    private NpcSight() {
    }

    /** 从 {@code from} 到 {@code to} 之间没有"不透明"方块。 */
    public static boolean canSee(Level level, Vec3 from, Vec3 to, Entity source) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.05D) {
            return true;
        }
        if (length > maxSightDistance()) {
            return false;
        }
        return traceVoxels(level, from, to, length);
    }

    /**
     * Amanatides &amp; Woo 3D DDA：从 from 到 to 依次经过的体素，遇到不透明方块返回 false。
     */
    private static boolean traceVoxels(Level level, Vec3 from, Vec3 to, double length) {
        double dx = (to.x - from.x) / length;
        double dy = (to.y - from.y) / length;
        double dz = (to.z - from.z) / length;

        int x = (int) Math.floor(from.x);
        int y = (int) Math.floor(from.y);
        int z = (int) Math.floor(from.z);

        int stepX = dx > 0.0D ? 1 : (dx < 0.0D ? -1 : 0);
        int stepY = dy > 0.0D ? 1 : (dy < 0.0D ? -1 : 0);
        int stepZ = dz > 0.0D ? 1 : (dz < 0.0D ? -1 : 0);

        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : intBound(from.x, dx);
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : intBound(from.y, dy);
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : intBound(from.z, dz);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dz);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // 起点所在方块本身不参与遮挡（例如站在玻璃里/贴着树叶）
        for (int guard = 0; guard < 1024; guard++) {
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    if (tMaxX > 1.0D) {
                        return true;
                    }
                    x += stepX;
                    tMaxX += tDeltaX;
                } else {
                    if (tMaxZ > 1.0D) {
                        return true;
                    }
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    if (tMaxY > 1.0D) {
                        return true;
                    }
                    y += stepY;
                    tMaxY += tDeltaY;
                } else {
                    if (tMaxZ > 1.0D) {
                        return true;
                    }
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (!isSeeThrough(level, cursor, state)) {
                return false;
            }
        }
        return true;
    }

    /** 归一化坐标在 dx 方向上的第一个体素边界 t 值。 */
    private static double intBound(double coordinate, double delta) {
        if (delta == 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        double offset = coordinate - Math.floor(coordinate);
        return delta > 0.0D ? (1.0D - offset) / delta : offset / -delta;
    }

    /**
     * 这个方块是否"看得穿"。
     *
     * <p>明确白名单：空气、水、树叶、玻璃类、玻璃板/栏杆、地毯、雪层，以及
     * 草/花/火把/铁轨等无碰撞小方块。其它实心方块（包括岩浆、火、细雪）一律挡视线。</p>
     */
    public static boolean isSeeThrough(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return true;
        }
        var fluid = state.getFluidState();
        if (fluid.is(FluidTags.WATER)) {
            return true;
        }
        // 岩浆不能透视
        if (fluid.is(FluidTags.LAVA)) {
            return false;
        }
        // 玻璃、染色玻璃、遮光玻璃、玻璃板、铁栏杆
        boolean throughGlass = com.npctacz.config.NpcGlobalConfig.getBoolean("vision_through_glass", true);
        if (throughGlass && (state.getBlock() instanceof HalfTransparentBlock
            || state.getBlock() instanceof IronBarsBlock
            || state.is(Blocks.TINTED_GLASS)
            || state.is(Blocks.GLASS_PANE)
            || state.is(Blocks.IRON_BARS))) {
            return true;
        }
        boolean throughLeaves = com.npctacz.config.NpcGlobalConfig.getBoolean("vision_through_leaves", true);
        if (throughLeaves && (state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock)) {
            return true;
        }
        // 地毯/雪层这种薄片不挡视线
        if (state.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock
            || state.getBlock() instanceof net.minecraft.world.level.block.SnowLayerBlock) {
            return true;
        }
        // 火/细雪虽然无碰撞，但遮视线
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
            || state.is(Blocks.POWDER_SNOW)) {
            return false;
        }
        // 草、花、火把、铁轨、按钮、脚手架等无碰撞小方块不挡视线
        return state.getCollisionShape(level, pos).isEmpty();
    }
}
