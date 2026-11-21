package com.npctacz.client;

import com.npctacz.ModRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Alt+N 打开全局 NPC 管理员界面。
 *
 * <p>用 KeyMapping 注册 N 键，再在 tick 里检查 Alt 是否按住：
 * 这样在“按键设置”里能看到并可改键，同时单独按 N 不会误触。</p>
 */
public final class NpcAdminKeybinds {

    /** 默认 N；玩家可在控制里改键，但打开仍要求 Alt 按住。 */
    public static final KeyMapping OPEN_ADMIN = new KeyMapping(
        "key.npctacz.admin", GLFW.GLFW_KEY_N, "key.categories.misc");

    private NpcAdminKeybinds() {
    }

    @Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.screen != null) {
                return;
            }
            while (OPEN_ADMIN.consumeClick()) {
                if (!isAltDown()) {
                    continue;
                }
                minecraft.setScreen(new NpcAdminScreen());
                return;
            }
        }

        private static boolean isAltDown() {
            long window = Minecraft.getInstance().getWindow().getWindow();
            return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
        }

        private static boolean isKeyDown(int key) {
            return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key);
        }
    }

    @Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void registerKeys(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
            event.register(OPEN_ADMIN);
        }
    }
}
