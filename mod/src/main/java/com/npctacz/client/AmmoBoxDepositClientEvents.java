package com.npctacz.client;

import com.npctacz.ModRegistry;
import com.npctacz.network.NpcNetwork;
import com.tacz.guns.api.item.IAmmoBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 弹药盒快速存入的客户端输入：
 * 左键双击（空点）→ 服务端判断双击并用手中弹药盒存入；
 * 背包内按住左键拖动弹药盒划过格子 → 发拖动存入请求。
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AmmoBoxDepositClientEvents {

    private static long lastDragTick = -100L;

    private AmmoBoxDepositClientEvents() {
    }

    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        if (event.getItemStack().getItem() instanceof IAmmoBox) {
            NpcNetwork.sendAdminUpdate("ammo_box_deposit", 0, 0.0D, 0.0D);
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Post event) {
        if (event.getMouseButton() != 0) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        ItemStack carried = screen.getMenu().getCarried();
        if (!(carried.getItem() instanceof IAmmoBox)) {
            return;
        }
        long now = Minecraft.getInstance().level == null
            ? 0L : Minecraft.getInstance().level.getGameTime();
        if (now - lastDragTick < 10L) {
            return;
        }
        lastDragTick = now;
        NpcNetwork.sendAdminUpdate("ammo_box_deposit", 0, 1.0D, 0.0D);
    }
}
