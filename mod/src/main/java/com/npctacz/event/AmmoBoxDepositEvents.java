package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 弹药盒快速存入：
 * <ul>
 *   <li>左键双击：手持弹药盒左键两次，存入背包内全部同种子弹；</li>
 *   <li>拖动：在背包里拿起弹药盒，按住左键划过格子，把全部同种子弹存入。</li>
 * </ul>
 * 实际动作由客户端发来的 AdminUpdateMessage("ammo_box_deposit") 触发。
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AmmoBoxDepositEvents {

    private AmmoBoxDepositEvents() {
    }

    /** @param drag true=滑动存入，false=左键双击 */
    public static void handleManual(ServerPlayer player, boolean drag) {
        ItemStack box;
        boolean carried;
        if (drag) {
            box = player.containerMenu.getCarried();
            carried = true;
        } else {
            box = findHeldBox(player);
            carried = false;
        }
        if (box.isEmpty() || !(box.getItem() instanceof IAmmoBox ammoBox)) {
            return;
        }
        if (ammoBox.isCreative(box) || ammoBox.isAllTypeCreative(box)) {
            return;
        }
        ResourceLocation ammoId = ammoBox.getAmmoId(box);
        if (ammoId == null || "tacz:empty".equals(ammoId.toString())) {
            return;
        }
        int moved = depositAllMatching(player, box, ammoBox, ammoId);
        if (carried) {
            player.containerMenu.setCarried(box);
            player.containerMenu.broadcastChanges();
        } else {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        if (moved > 0) {
            player.displayClientMessage(Component.literal(
                "§a已存入 " + moved + " 发同种子弹"
            ), true);
        } else {
            player.displayClientMessage(Component.literal(
                "§7背包里没有可存入的同种子弹"
            ), true);
        }
    }

    private static ItemStack findHeldBox(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof IAmmoBox) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        return off.getItem() instanceof IAmmoBox ? off : ItemStack.EMPTY;
    }

    private static int depositAllMatching(ServerPlayer player, ItemStack box,
                                          IAmmoBox ammoBox, ResourceLocation ammoId) {
        int moved = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
            if (ammo == null || !ammoId.equals(ammo.getAmmoId(stack))) {
                continue;
            }
            moved += stack.getCount();
            inventory.setItem(i, ItemStack.EMPTY);
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty()) {
            IAmmo ammo = IAmmo.getIAmmoOrNull(offhand);
            if (ammo != null && ammoId.equals(ammo.getAmmoId(offhand))) {
                moved += offhand.getCount();
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            }
        }
        if (moved > 0) {
            ammoBox.setAmmoCount(box, ammoBox.getAmmoCount(box) + moved);
        }
        return moved;
    }
}
