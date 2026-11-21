package com.npctacz.client;

import com.npctacz.ModRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * NPC 子弹命中玩家时的“动态后坐力”客户端表现。
 *
 * <p>不是直接把视角抬上去再线性回正，而是用弹簧-阻尼模型：
 * 先被子弹的冲量向上推，越过最高点后向下回摆，再逐渐稳定。
 * 也就是玩家会感觉到“先上、再下、再回正”。</p>
 *
 * <p>服务端只发一个初始 yaw/pitch 冲量，所有平滑/回摆都在客户端做，
 * 不会污染服务端玩家真实朝向。</p>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HitKickHandler {

    /** 冲量到角速度的转换；越大第一下越猛。 */
    private static final float PITCH_IMPULSE = 0.55F;
    private static final float YAW_IMPULSE = 0.42F;
    /** 弹簧回到 0 的强度；越大回正越快。 */
    private static final float PITCH_SPRING = 0.32F;
    private static final float YAW_SPRING = 0.28F;
    /** 阻尼；0.72 会保留一点回摆，太低会抖很久。 */
    private static final float PITCH_DAMPING = 0.72F;
    private static final float YAW_DAMPING = 0.76F;
    /** 最大偏移/角速度，防止连续命中把视角甩飞。 */
    private static final float MAX_OFFSET = 14.0F;
    private static final float MAX_VELOCITY = 18.0F;
    /** 低于这个偏移就归零，避免无限小数抖动。 */
    private static final float REST_EPSILON = 0.01F;

    private static float pitchOffset;
    private static float pitchVelocity;
    private static float yawOffset;
    private static float yawVelocity;
    /** 上一 tick 的偏移，用于渲染帧插值，避免 20Hz 一跳一跳。 */
    private static float prevPitchOffset;
    private static float prevYawOffset;

    private HitKickHandler() {
    }

    /** 收到网络包：把服务端发来的 yaw/pitch 转成弹簧冲量，而不是直接改视角。 */
    public static void add(float yawKick, float pitchKick) {
        // 负 pitch = 视角向上；弹簧会先向上，再向下回摆。
        pitchVelocity = Mth.clamp(pitchVelocity + pitchKick * PITCH_IMPULSE,
            -MAX_VELOCITY, MAX_VELOCITY);
        yawVelocity = Mth.clamp(yawVelocity + yawKick * YAW_IMPULSE,
            -MAX_VELOCITY, MAX_VELOCITY);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            pitchOffset = 0.0F;
            pitchVelocity = 0.0F;
            yawOffset = 0.0F;
            yawVelocity = 0.0F;
            return;
        }
        if (Math.abs(pitchOffset) < REST_EPSILON && Math.abs(pitchVelocity) < REST_EPSILON
            && Math.abs(yawOffset) < REST_EPSILON && Math.abs(yawVelocity) < REST_EPSILON) {
            pitchOffset = 0.0F;
            pitchVelocity = 0.0F;
            yawOffset = 0.0F;
            yawVelocity = 0.0F;
            return;
        }

        // 记录上一 tick 偏移，渲染帧用 partialTick 插值，视角变化更平滑。
        prevPitchOffset = pitchOffset;
        prevYawOffset = yawOffset;

        // 弹簧-阻尼积分：先被冲量推出去，再被弹簧拉回并越过 0 点，形成“上→下→回正”。
        pitchVelocity += -pitchOffset * PITCH_SPRING;
        pitchVelocity *= PITCH_DAMPING;
        float nextPitch = Mth.clamp(pitchOffset + pitchVelocity, -MAX_OFFSET, MAX_OFFSET);
        float deltaPitch = nextPitch - pitchOffset;
        pitchOffset = nextPitch;

        yawVelocity += -yawOffset * YAW_SPRING;
        yawVelocity *= YAW_DAMPING;
        float nextYaw = Mth.clamp(yawOffset + yawVelocity, -MAX_OFFSET, MAX_OFFSET);
        float deltaYaw = nextYaw - yawOffset;
        yawOffset = nextYaw;

        // 不再直接改 player rotation，交给 ComputeCameraAngles 每帧插值，避免视角跳动/漂移。
    }

    /** 每帧相机角度插值：把 20Hz 的弹簧偏移平滑到当前渲染帧。 */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        float partial = (float) event.getPartialTick();
        float pitch = Mth.lerp(partial, prevPitchOffset, pitchOffset);
        float yaw = Mth.lerp(partial, prevYawOffset, yawOffset);
        if (Math.abs(pitch) < 0.001F && Math.abs(yaw) < 0.001F) {
            return;
        }
        event.setPitch(Mth.clamp(event.getPitch() + pitch, -90.0F, 90.0F));
        event.setYaw(event.getYaw() + yaw);
    }
}
