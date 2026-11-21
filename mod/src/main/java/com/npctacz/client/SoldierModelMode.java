package com.npctacz.client;

/**
 * 士兵 NPC 的渲染模式（客户端开关，/npcmodel skin|glb 切换，立即生效）。
 *
 * <ul>
 *   <li>{@code skin}（默认）：使用玩家提供的士兵皮肤模型（64x64 humanoid）；</li>
 *   <li>{@code glb}：使用朋友制作的现代士兵 GLB 模型（带程序化抬枪/后坐力/换弹动作）。</li>
 * </ul>
 */
public final class SoldierModelMode {

    private static volatile boolean useGlb = false;

    public static boolean useGlb() {
        return useGlb;
    }

    public static void setUseGlb(boolean value) {
        useGlb = value;
    }

    private SoldierModelMode() {
    }
}
