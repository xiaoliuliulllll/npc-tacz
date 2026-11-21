package com.npctacz.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S：打开管理员界面时请求一份最新快照（服务端会附带 canEdit 权限）。 */
public final class AdminSyncRequestMessage {

    public AdminSyncRequestMessage() {
    }

    public static void encode(AdminSyncRequestMessage message, FriendlyByteBuf buffer) {
    }

    public static AdminSyncRequestMessage decode(FriendlyByteBuf buffer) {
        return new AdminSyncRequestMessage();
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                NpcNetwork.sendAdminSettings(sender, AdminSettingsMessage.snapshot(sender));
            }
        });
        context.setPacketHandled(true);
    }
}
