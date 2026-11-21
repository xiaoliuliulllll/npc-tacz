package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Player-kill taunt: when an NPC kills a player, that NPC stops and spams the
 * crouch pose for 3-6 seconds (the same visual as rapidly pressing Shift while
 * holding a TACZ gun).
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcTauntEvents {

    private NpcTauntEvents() {
    }

    @SubscribeEvent
    public static void onPlayerKilled(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Entity source = event.getSource().getEntity();
        if (!(source instanceof WastelandNpcEntity) && event.getSource().getDirectEntity() instanceof Projectile projectile
            && projectile.getOwner() instanceof WastelandNpcEntity) {
            source = projectile.getOwner();
        }
        if (!(source instanceof WastelandNpcEntity npc) || !npc.isAlive()) {
            return;
        }
        npc.startTaunt();
    }
}
