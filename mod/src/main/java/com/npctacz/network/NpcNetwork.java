package com.npctacz.network;

import com.npctacz.ModRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 本模组唯一的网络通道（协议安全：只发送自己的消息，不碰其它模组的数据）。
 *
 * <p>目前只有一条：NPC 子弹命中玩家时给玩家发一个"视角后坐力"反馈。</p>
 */
public final class NpcNetwork {

    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(new ResourceLocation(ModRegistry.MOD_ID, "main"))
        .networkProtocolVersion(() -> VERSION)
        .clientAcceptedVersions(VERSION::equals)
        .serverAcceptedVersions(VERSION::equals)
        .simpleChannel();

    private static int nextMessageId;

    private NpcNetwork() {
    }

    public static void init() {
        CHANNEL.messageBuilder(HitKickMessage.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(HitKickMessage::encode)
            .decoder(HitKickMessage::decode)
            .consumerMainThread(HitKickMessage::handle)
            .add();
        CHANNEL.messageBuilder(AdminSyncRequestMessage.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(AdminSyncRequestMessage::encode)
            .decoder(AdminSyncRequestMessage::decode)
            .consumerMainThread(AdminSyncRequestMessage::handle)
            .add();
        CHANNEL.messageBuilder(AdminUpdateMessage.class, nextMessageId++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(AdminUpdateMessage::encode)
            .decoder(AdminUpdateMessage::decode)
            .consumerMainThread(AdminUpdateMessage::handle)
            .add();
        CHANNEL.messageBuilder(AdminSettingsMessage.class, nextMessageId++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(AdminSettingsMessage::encode)
            .decoder(AdminSettingsMessage::decode)
            .consumerMainThread(AdminSettingsMessage::handle)
            .add();
    }

    public static void requestAdminSettings() {
        try {
            CHANNEL.sendToServer(new AdminSyncRequestMessage());
        } catch (Throwable ignored) {
            // 网络异常不能让界面崩溃
        }
    }

    public static void sendAdminSettings(ServerPlayer player, AdminSettingsMessage message) {
        if (player == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        } catch (Throwable ignored) {
            // 网络异常不能让界面崩溃
        }
    }

    public static void sendAdminUpdate(String action, int faction, double valueA, double valueB) {
        try {
            CHANNEL.sendToServer(new AdminUpdateMessage(action, faction, valueA, valueB));
        } catch (Throwable ignored) {
            // 网络异常不能让界面崩溃
        }
    }

    /**
     * 给被 NPC 子弹命中的玩家发送轻微视角上跳。
     *
     * @param yawKick   左右偏移（度，正负随机）
     * @param pitchKick 俯仰偏移（度，负值 = 视角向上）
     */
    public static void sendHitKick(ServerPlayer player, float yawKick, float pitchKick) {
        if (player == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new HitKickMessage(yawKick, pitchKick));
        } catch (Throwable ignored) {
            // 网络异常不能影响战斗结算
        }
    }
}
