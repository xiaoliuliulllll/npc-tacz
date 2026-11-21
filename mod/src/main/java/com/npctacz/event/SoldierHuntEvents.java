package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.data.NpcDifficultyData;
import com.npctacz.data.SoldierHuntData;
import com.npctacz.entity.SoldierNpc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Starts and schedules the three-day soldier pursuit.
 *
 * <p>Only the overworld is used as the hunt clock because the NPC population is
 * overworld-based. The noon check is intentionally a narrow window: it runs once
 * per day, snapshots the soldiers that exist at that instant, and never touches
 * soldiers spawned later that day.</p>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SoldierHuntEvents {

    private static final int NOON_START = 12_000;
    private static final int NOON_WINDOW = 20;
    private static final float HUNT_SOLDIER_FRACTION = 0.40F;

    private SoldierHuntEvents() {
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof SoldierNpc)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) {
            return;
        }
        ServerLevel level = killer.serverLevel();
        SoldierHuntData data = SoldierHuntData.get(level);
        // startHunt() is a no-op while the current three days are still active,
        // so repeated kills never stack or refresh the timer.
        if (data.startHunt(killer, level.getGameTime())
            && NpcDifficultyData.get(level).areChatNotificationsEnabled(killer.getUUID())) {
            killer.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "§c你击杀了一名士兵，士兵将追杀你 3 天。"
            ), false);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        SoldierHuntData data = SoldierHuntData.get(level);
        long gameTime = level.getGameTime();
        data.purge(gameTime);

        long dayTime = level.getDayTime();
        int timeOfDay = (int) Math.floorMod(dayTime, 24_000L);
        if (timeOfDay < NOON_START || timeOfDay >= NOON_START + NOON_WINDOW) {
            return;
        }
        long day = dayTime / 24_000L;
        if (!data.markHuntNoon(day)) {
            return;
        }
        assignNoonHunters(server, level, data, gameTime);
    }

    private static void assignNoonHunters(MinecraftServer server, ServerLevel level,
                                          SoldierHuntData data, long gameTime) {
        List<ServerPlayer> huntedPlayers = new ArrayList<>();
        for (UUID uuid : data.activeHunts(gameTime).keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && player.level() == level && !player.isSpectator()) {
                huntedPlayers.add(player);
            }
        }
        if (huntedPlayers.isEmpty()) {
            return;
        }

        List<SoldierNpc> soldiers = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof SoldierNpc soldier && soldier.isAlive()) {
                soldiers.add(soldier);
            }
        }
        if (soldiers.isEmpty()) {
            return;
        }
        Collections.shuffle(soldiers, new java.util.Random(level.getRandom().nextLong()));
        int count = Math.max(1, Math.round(soldiers.size() * HUNT_SOLDIER_FRACTION));
        for (int i = 0; i < count; i++) {
            SoldierNpc soldier = soldiers.get(i);
            ServerPlayer target = huntedPlayers.get(level.getRandom().nextInt(huntedPlayers.size()));
            soldier.assignHunt(target, target.blockPosition(), gameTime);
        }
    }
}
