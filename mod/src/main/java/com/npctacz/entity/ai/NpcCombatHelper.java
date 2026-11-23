package com.npctacz.entity.ai;

import com.npctacz.config.NpcConfig;
import com.npctacz.entity.NpcFaction;
import com.npctacz.entity.NpcSight;
import com.npctacz.entity.SoldierNpc;
import com.npctacz.entity.WastelandNpcEntity;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * NPC 战斗通用工具：视线、索敌评分、友军射界、掩体点、侧翼点、地面点。
 *
 * <p>全部是纯函数/只读查询，不修改 NPC 状态，方便被多个 Goal 复用。</p>
 */
public final class NpcCombatHelper {

    /** 索敌时非玩家实体的最大扫描半径（防止一次 AABB 查询拖慢服务器）。 */
    private static final double MOB_SCAN_RADIUS = 48.0D;

    /** 友军射界夹角（度）：目标方向上这个角度内的友军算挡枪。 */
    private static final double FIRE_LINE_ALLY_ANGLE = 11.0D;

    private NpcCombatHelper() {
    }

    /** 是否能看到目标（玻璃/树叶/栏杆可看穿）。 */
    public static boolean canSee(WastelandNpcEntity npc, LivingEntity target) {
        return NpcSight.canSee(npc.level(), npc.getEyePosition(), target.getEyePosition(), npc);
    }

    /**
     * 瞄准点：目标眼睛位置 + 少量提前量（打移动目标时枪口稍微往前送一点）。
     */
    public static Vec3 aimPoint(WastelandNpcEntity npc, LivingEntity target) {
        Vec3 eyes = target.getEyePosition();
        Vec3 velocity = target.getDeltaMovement();
        double distance = Math.sqrt(npc.distanceToSqr(target));
        double lead = Math.min(1.6D, distance * 0.02D);
        Vec3 horizontal = new Vec3(velocity.x, 0.0D, velocity.z);
        if (horizontal.lengthSqr() > 1.0E-4D) {
            eyes = eyes.add(horizontal.normalize().scale(lead));
        }
        return eyes;
    }

    /**
     * 在当前视野半径内挑一个最合适的目标。
     *
     * <p>评分 = 距离 + 视线 + 当前目标粘性 + 刚刚被谁打过 + 低血量优先。
     * 玩家单独用感知范围扫描（默认 96 格），实体只用 {@code min(radius, 48)}。</p>
     */
    @Nullable
    /** 每 tick 全局索敌扫描预算，NPC 很多时分摊到后续 tick，避免掉 TPS。 */
    private static long scanBudgetTick = Long.MIN_VALUE;
    private static int scanBudgetLeft;
    private static final int SCAN_BUDGET_PER_TICK = 12;

    public static LivingEntity scanForTarget(WastelandNpcEntity npc, double radius) {
        long tick = npc.level().getGameTime();
        if (tick != scanBudgetTick) {
            scanBudgetTick = tick;
            scanBudgetLeft = SCAN_BUDGET_PER_TICK;
        }
        if (scanBudgetLeft-- <= 0) {
            return null;
        }
        LivingEntity current = npc.getTarget();
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double switchBonus = NpcConfig.VALUES.targetSwitchCost.get() + 0.3D;

        double playerRadius = Math.max(radius, npc.getAwarenessRange());
        for (Player player : npc.level().players()) {
            if (!npc.isHostileTo(player) || !npc.isWithinPlayerViewDistance(player)) {
                continue;
            }
            double distanceSqr = npc.distanceToSqr(player);
            if (distanceSqr > playerRadius * playerRadius) {
                continue;
            }
            if (player != current && !npc.isTargetInFrontArc(player, 75.0F)
                && !npc.isLoudTarget(player)) {
                continue;
            }
            boolean visible = canSee(npc, player);
            if (!visible && player != current) {
                // 墙后不能"看到"新目标；已经是当前目标则保留记忆
                continue;
            }
            double score = score(npc, player, Math.sqrt(distanceSqr), playerRadius, visible);
            if (player == current) {
                score += switchBonus;
            }
            if (npc.getLastHurtByMob() == player) {
                score += 0.5D;
            }
            if (score > bestScore) {
                bestScore = score;
                best = player;
            }
        }

        double mobRadius = Math.min(radius, MOB_SCAN_RADIUS);
        AABB box = npc.getBoundingBox().inflate(mobRadius, 24.0D, mobRadius);
        List<LivingEntity> candidates = npc.level().getEntitiesOfClass(
            LivingEntity.class, box,
            other -> other != npc && other.isAlive() && !(other instanceof Player) && npc.isHostileTo(other)
        );
        for (LivingEntity candidate : candidates) {
            double distanceSqr = npc.distanceToSqr(candidate);
            if (distanceSqr > mobRadius * mobRadius) {
                continue;
            }
            if (candidate != current && !npc.isTargetInFrontArc(candidate, 75.0F)
                && !npc.isLoudTarget(candidate)) {
                continue;
            }
            boolean visible = canSee(npc, candidate);
            if (!visible && candidate != current) {
                continue;
            }
            double score = score(npc, candidate, Math.sqrt(distanceSqr), mobRadius, visible);
            if (candidate instanceof net.minecraft.world.entity.monster.Enemy) {
                // 生物威胁：正在攻击自己/正在攻击队友的怪物优先。
                if (npc.getLastHurtByMob() == candidate) {
                    score += 0.8D;
                }
                if (candidate instanceof net.minecraft.world.entity.Mob mob
                    && mob.getTarget() == npc) {
                    score += 0.35D;
                }
            }
            if (candidate == current) {
                score += switchBonus;
            }
            if (npc.getLastHurtByMob() == candidate) {
                score += 0.5D;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /** 玩家主手/副手是否拿着枪械或近战/远程武器。 */
    public static boolean isArmedPlayer(LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        return isWeapon(player.getMainHandItem()) || isWeapon(player.getOffhandItem());
    }

    public static boolean isWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (IGun.getIGunOrNull(stack) != null) {
            return true;
        }
        Item item = stack.getItem();
        if (item instanceof SwordItem || item instanceof AxeItem || item instanceof TridentItem
            || item instanceof BowItem || item instanceof CrossbowItem || item instanceof ShieldItem) {
            return true;
        }
        return stack.getAttributeModifiers(EquipmentSlot.MAINHAND)
            .get(Attributes.ATTACK_DAMAGE).stream()
            .anyMatch(modifier -> modifier.getAmount() > 1.0D);
    }

    /** 附近最近且对 NPC 敌对的持武器玩家。 */
    @Nullable
    public static Player findNearestArmedPlayer(WastelandNpcEntity npc, double radius) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : npc.level().getEntitiesOfClass(
            Player.class, npc.getBoundingBox().inflate(radius), Player::isAlive)) {
            if (!npc.isHostileTo(player) || !isArmedPlayer(player)) {
                continue;
            }
            double distance = npc.distanceToSqr(player);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    public static int targetPriority(LivingEntity candidate) {
        if (candidate instanceof net.minecraft.world.entity.monster.Enemy) {
            return 0;
        }
        if (candidate instanceof Player) {
            return 2;
        }
        if (candidate instanceof WastelandNpcEntity) {
            return 2;
        }
        return 2;
    }

    /**
     * 阵营目标优先级（数值越小越优先）：
     * 士兵/叛军：敌对生物0，士兵NPC1，其他NPC2，玩家3；
     * 劫匪：敌对生物0，NPC2，玩家3；
     * 赏金猎人：敌对生物0，玩家1，NPC2；
     * 雇佣兵：敌对生物0，玩家/NPC2。
     */
    public static int targetPriority(WastelandNpcEntity npc, LivingEntity candidate) {
        if (candidate instanceof net.minecraft.world.entity.monster.Enemy) {
            return 0;
        }
        if (npc == null) {
            return targetPriority(candidate);
        }
        NpcFaction faction = npc.getFaction();
        if (candidate instanceof Player) {
            if (faction == NpcFaction.BOUNTY_HUNTER) return 1;
            if (faction == NpcFaction.MERCENARY) return 2;
            return 3;
        }
        if (candidate instanceof WastelandNpcEntity otherNpc) {
            if ((faction == NpcFaction.SOLDIER || faction == NpcFaction.REBEL)
                && otherNpc instanceof SoldierNpc) {
                return 1;
            }
            if (faction == NpcFaction.BOUNTY_HUNTER) return 2;
            return 2;
        }
        return 2;
    }

    private static double score(WastelandNpcEntity npc, LivingEntity candidate, double distance,
                                double radius, boolean visible) {
        // 普通索敌只看距离：越近越高分；反击攻击者由 forcedTarget 单独置顶。
        double score = -distance;
        if (visible) {
            score += 0.35D;
        } else if (distance < 20.0D) {
            score -= 0.05D;
        } else {
            score -= 0.2D;
        }
        if (candidate.getHealth() / Math.max(1.0F, candidate.getMaxHealth()) < 0.35F) {
            score += 0.1D;
        }
        // 拿武器/枪械的玩家：最高优先级，距离再远也优先于普通目标。
        if (candidate instanceof Player player && isArmedPlayer(player)) {
            score += 10000.0D;
        }
        return score;
    }

    /**
     * 目标方向上是否夹着自己人：夹着就不开枪（防误伤/挡弹）。
     */
    public static boolean allyInFireLine(WastelandNpcEntity npc, LivingEntity target, double maxDistance) {
        if (!com.npctacz.config.NpcGlobalConfig.getBoolean(
            "friendly_fire_safety", NpcConfig.VALUES.friendlyFireSafety.get())) {
            return false;
        }
        Vec3 origin = npc.getEyePosition();
        Vec3 toTarget = target.getEyePosition().subtract(origin);
        double targetDistance = toTarget.length();
        if (targetDistance < 0.5D) {
            return false;
        }
        Vec3 forward = toTarget.scale(1.0D / targetDistance);
        AABB box = npc.getBoundingBox().inflate(maxDistance, 8.0D, maxDistance);
        List<WastelandNpcEntity> allies = npc.level().getEntitiesOfClass(
            WastelandNpcEntity.class, box,
            ally -> ally != npc && ally.isAlive() && ally.getFaction() == npc.getFaction()
        );
        double cosLimit = Math.cos(Math.toRadians(FIRE_LINE_ALLY_ANGLE));
        for (WastelandNpcEntity ally : allies) {
            Vec3 toAlly = ally.getEyePosition().subtract(origin);
            double allyDistance = toAlly.length();
            if (allyDistance < 0.8D || allyDistance > targetDistance + 1.5D) {
                continue;
            }
            if (toAlly.scale(1.0D / allyDistance).dot(forward) >= cosLimit) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找一个可以挡视线、且地面能站人的掩体点（返回脚部坐标）。
     */
    @Nullable
    public static Vec3 findCoverPoint(WastelandNpcEntity npc, LivingEntity threat, int radius) {
        Level level = npc.level();
        Vec3 origin = npc.position();
        Vec3 threatEye = threat.getEyePosition();
        double preferred = npc.getPreferredRange();
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        int rings = 3;
        int directions = 12;
        for (int ring = 1; ring <= rings; ring++) {
            double distance = radius * ring / (double) rings;
            for (int i = 0; i < directions; i++) {
                double angle = i * (Math.PI * 2.0D / directions);
                double x = origin.x + Math.cos(angle) * distance;
                double z = origin.z + Math.sin(angle) * distance;
                Vec3 spot = findStandingSpot(level, x, npc.getY(), z);
                if (spot == null) {
                    continue;
                }
                Vec3 eye = spot.add(0.0D, npc.getEyeHeight(), 0.0D);
                BlockHitResult hit = level.clip(new ClipContext(
                    eye, threatEye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc
                ));
                if (hit.getType() == HitResult.Type.MISS) {
                    continue;
                }
                if (hit.getLocation().distanceTo(eye) < 0.7D) {
                    continue;
                }
                double coverDistance = spot.distanceTo(threat.position());
                double score = spot.distanceTo(origin) * 0.6D
                    + Math.abs(coverDistance - preferred) * 0.4D;
                if (score < bestScore) {
                    bestScore = score;
                    best = spot;
                }
            }
        }
        return best;
    }

    /** 找一个能绕到目标侧面、且有视线的点。 */
    @Nullable
    public static Vec3 findFlankPoint(WastelandNpcEntity npc, LivingEntity target) {
        Level level = npc.level();
        Vec3 away = npc.position().subtract(target.position());
        away = new Vec3(away.x, 0.0D, away.z);
        if (away.lengthSqr() < 1.0E-4D) {
            away = new Vec3(1.0D, 0.0D, 0.0D);
        }
        away = away.normalize();
        Vec3 side = new Vec3(-away.z, 0.0D, away.x);
        double range = Math.max(4.0D, npc.getPreferredRange());
        for (int i = 0; i < 6; i++) {
            double sideMultiplier = (i % 2 == 0 ? 1.0D : -1.0D) * (1.0D + i / 2.0D);
            Vec3 candidate = target.position()
                .add(away.scale(range * 0.9D))
                .add(side.scale(sideMultiplier * 3.0D));
            Vec3 spot = findStandingSpot(level, candidate.x, target.getY(), candidate.z);
            if (spot == null) {
                continue;
            }
            Vec3 eye = spot.add(0.0D, npc.getEyeHeight(), 0.0D);
            if (NpcSight.canSee(level, eye, target.getEyePosition(), npc)) {
                return spot;
            }
        }
        return null;
    }

    /** 横向走位点（side = ±1）。 */
    public static Vec3 sideStep(WastelandNpcEntity npc, LivingEntity target, double side, double distance) {
        Vec3 direction = target.position().subtract(npc.position());
        direction = new Vec3(direction.x, 0.0D, direction.z);
        if (direction.lengthSqr() < 1.0E-4D) {
            direction = new Vec3(1.0D, 0.0D, 0.0D);
        }
        Vec3 lateral = new Vec3(-direction.z, 0.0D, direction.x).normalize().scale(side * distance);
        return npc.position().add(lateral);
    }

    /** 后撤点：沿 NPC→目标反方向退开指定距离。 */
    public static Vec3 retreatPoint(WastelandNpcEntity npc, LivingEntity target, double distance) {
        Vec3 away = npc.position().subtract(target.position());
        away = new Vec3(away.x, 0.0D, away.z);
        if (away.lengthSqr() < 1.0E-4D) {
            away = new Vec3(1.0D, 0.0D, 0.0D);
        }
        return npc.position().add(away.normalize().scale(distance));
    }

    /** 在 (x, y±2, z) 附近找一块能站人的地面（脚下实心、上方两格可站）。 */
    @Nullable
    public static Vec3 findStandingSpot(Level level, double x, double y, double z) {
        BlockPos center = BlockPos.containing(x, y, z);
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos feet = center.offset(0, dy, 0);
            if (!canStandAt(level, feet)) {
                continue;
            }
            return new Vec3(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
        }
        return null;
    }

    /** 这个方块位置能否站人：下方实心、自身和头顶两格不是固体/液体。 */
    public static boolean canStandAt(Level level, BlockPos feet) {
        BlockState below = level.getBlockState(feet.below());
        if (below.isAir() || !below.getFluidState().isEmpty()) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            BlockState state = level.getBlockState(feet.offset(0, i, 0));
            if (!state.isAir() && state.getFluidState().isEmpty() && state.blocksMotion()) {
                return false;
            }
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 移动中开枪的额外散布（度）。 */
    public static float movingSpread(WastelandNpcEntity npc) {
        double speedSqr = npc.getDeltaMovement().horizontalDistanceSqr();
        if (speedSqr < 0.0025D) {
            return 0.0F;
        }
        // 移动射击时散布大幅增加；站定后归 0，鼓励 NPC 停下来打。
        float extra = 4.0F + (float) Math.sqrt(speedSqr) * 12.0F;
        return Math.min(15.0F, extra);
    }

    /** 目标是否被自己人挡着（近战冲锋前的友情检查）。 */
    public static boolean friendFartherThanUs(WastelandNpcEntity npc, LivingEntity target) {
        double ourDistance = npc.distanceToSqr(target);
        AABB box = npc.getBoundingBox().inflate(4.0D, 4.0D, 4.0D);
        for (WastelandNpcEntity ally : npc.level().getEntitiesOfClass(
            WastelandNpcEntity.class, box,
            other -> other != npc && other.isAlive() && other.getFaction() == npc.getFaction()
        )) {
            if (ally.distanceToSqr(target) < ourDistance - 1.0D) {
                return true;
            }
        }
        return false;
    }
}
