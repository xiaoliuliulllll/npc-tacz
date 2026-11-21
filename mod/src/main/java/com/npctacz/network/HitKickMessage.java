package com.npctacz.network;

import com.npctacz.client.HitKickHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：NPC 子弹命中玩家时给一点视角后坐力。
 */
public final class HitKickMessage {

    private final float yawKick;
    private final float pitchKick;

    public HitKickMessage(float yawKick, float pitchKick) {
        this.yawKick = yawKick;
        this.pitchKick = pitchKick;
    }

    public static void encode(HitKickMessage message, FriendlyByteBuf buffer) {
        buffer.writeFloat(message.yawKick);
        buffer.writeFloat(message.pitchKick);
    }

    public static HitKickMessage decode(FriendlyByteBuf buffer) {
        return new HitKickMessage(buffer.readFloat(), buffer.readFloat());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        // DistExecutor 保证 dedicated server 下不会加载客户端类
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> HitKickHandler.add(this.yawKick, this.pitchKick)));
        context.setPacketHandled(true);
    }
}
