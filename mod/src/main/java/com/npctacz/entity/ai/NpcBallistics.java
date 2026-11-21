package com.npctacz.entity.ai;

import com.npctacz.entity.WastelandNpcEntity;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * NPC 弹道解算。
 *
 * <p>TACZ 的子弹不是射线：每 tick 先按当前速度移动，然后
 * {@code velocity *= (1 - friction)}、{@code velocity.y -= gravity}（见 EntityKineticBullet.tick）。
 * 所以远距离直接瞄目标会打低。这里按枪械 BulletData 的 speed/gravity/friction 做
 * 提前量 + 下坠补偿，输出一个“瞄准点”，再交给 yaw/pitch 计算。</p>
 */
public final class NpcBallistics {

    private static final double TICKS_PER_SECOND = 20.0D;

    private NpcBallistics() {
    }

    /**
     * @param bullet 当前枪械/弹药的弹道数据；null 时退回普通提前量瞄准
     * @return 世界坐标中的瞄准点（含目标移动提前量与重力下坠补偿）
     */
    public static Vec3 aimPoint(WastelandNpcEntity npc, LivingEntity target, @Nullable BulletData bullet) {
        if (bullet == null) {
            return NpcCombatHelper.aimPoint(npc, target);
        }
        Vec3 eye = npc.getEyePosition();
        Vec3 targetEye = target.getEyePosition();
        Vec3 targetVelocity = target.getDeltaMovement();

        // TACZ 内部速度单位：JSON 里的 speed / 20 = 每 tick 格数
        double speedPerTick = bullet.getSpeed() / TICKS_PER_SECOND;
        try {
            // TACZ 实际初速 = bullet.speed * [ammo] GlobalBulletSpeedModifier / 20
            speedPerTick *= com.tacz.guns.config.common.AmmoConfig.GLOBAL_BULLET_SPEED_MODIFIER.get();
        } catch (Throwable ignored) {
            // TACZ 版本不支持该配置时忽略
        }
        double gravity = Math.max(0.0D, bullet.getGravity());
        double friction = Math.max(0.0D, Math.min(0.9D, bullet.getFriction()));
        if (speedPerTick < 0.05D) {
            return NpcCombatHelper.aimPoint(npc, target);
        }
        // 摩擦让平均速度略低于初速，用 (1 - friction/2) 近似
        double travelSpeed = Math.max(0.05D, speedPerTick * (1.0D - friction * 0.5D));

        double flightTicks = eye.distanceTo(targetEye) / travelSpeed;
        Vec3 aim = targetEye;
        for (int iteration = 0; iteration < 3; iteration++) {
            flightTicks = Math.min(flightTicks, 60.0D);
            Vec3 predicted = targetEye.add(targetVelocity.scale(flightTicks));
            double drop = 0.5D * gravity * flightTicks * flightTicks;
            aim = predicted.add(0.0D, -drop, 0.0D);
            flightTicks = eye.distanceTo(predicted) / travelSpeed;
        }
        return aim;
    }
}
