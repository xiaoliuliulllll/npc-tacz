package com.npctacz.client;

import com.npctacz.network.AdminSettingsMessage;

/** 客户端当前玩家的管理员界面设置（目前主要是个人可见/受击距离）。 */
public final class NpcAdminClientSettings {

    public static final int DEFAULT_VIEW_DISTANCE = 96;
    public static final int MIN_VIEW_DISTANCE = 32;
    public static final int MAX_VIEW_DISTANCE = 10240;

    private static volatile int viewDistance = DEFAULT_VIEW_DISTANCE;
    private static volatile boolean cullEnabled = false;
    private static volatile int cullDistance = 96;

    private NpcAdminClientSettings() {
    }

    public static int getViewDistance() {
        return viewDistance;
    }

    public static boolean isCullEnabled() {
        return cullEnabled;
    }

    public static int getCullDistance() {
        return cullDistance;
    }

    /** 实际剔除距离：0 = 不剔除；开启时使用统一的个人距离。 */
    public static int effectiveViewDistance() {
        return cullEnabled ? viewDistance : 0;
    }

    public static void setViewDistance(int value) {
        viewDistance = Math.max(MIN_VIEW_DISTANCE, Math.min(MAX_VIEW_DISTANCE, value));
    }

    /** 网络快照到达时更新客户端设置，并转给正在打开的管理员界面。 */
    public static void applySnapshot(AdminSettingsMessage message) {
        setViewDistance(message.viewDistance);
        cullEnabled = message.npcCullEnabled;
        cullDistance = Math.max(32, Math.min(1024, message.npcCullDistance));
        NpcAdminScreen.applySnapshot(message);
    }
}
