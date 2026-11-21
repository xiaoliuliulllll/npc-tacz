package com.npctacz.network;

import com.npctacz.data.NpcDifficultyData;
import com.npctacz.data.NpcSpawnConfigData;
import com.npctacz.entity.NpcFaction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：管理员修改一个字段。服务端必须再次校验 OP 权限。
 *
 * <p>action 取值：damage / firerate / difficulty / drop_guns / drop_ammo / ammo_multiplier /
 * spawn_amount / spawn_interval / spawn_interval_all。</p>
 */
public final class AdminUpdateMessage {

    private final String action;
    private final int faction;
    private final double valueA;
    private final double valueB;

    public AdminUpdateMessage(String action, int faction, double valueA, double valueB) {
        this.action = action == null ? "" : action;
        this.faction = faction;
        this.valueA = valueA;
        this.valueB = valueB;
    }

    public static void encode(AdminUpdateMessage message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.action, 48);
        buffer.writeVarInt(message.faction);
        buffer.writeDouble(message.valueA);
        buffer.writeDouble(message.valueB);
    }

    public static AdminUpdateMessage decode(FriendlyByteBuf buffer) {
        return new AdminUpdateMessage(buffer.readUtf(48), buffer.readVarInt(),
            buffer.readDouble(), buffer.readDouble());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            NpcDifficultyData difficulty = NpcDifficultyData.get(sender.serverLevel());
            NpcSpawnConfigData spawn = NpcSpawnConfigData.get(sender.serverLevel());
            // 个人可见/受击距离：任何玩家都可以设置自己的值，不影响别人
            if ("view_distance".equals(this.action)) {
                difficulty.setPlayerViewDistance(sender.getUUID(), (int) Math.round(this.valueA));
                com.npctacz.event.NpcAdminDefaults.save(sender.getServer());
                NpcNetwork.sendAdminSettings(sender, AdminSettingsMessage.snapshot(sender));
                return;
            }
            if ("chat_notifications".equals(this.action)) {
                difficulty.setChatNotificationsEnabled(sender.getUUID(), this.valueA != 0.0D);
                com.npctacz.event.NpcAdminDefaults.save(sender.getServer());
                NpcNetwork.sendAdminSettings(sender, AdminSettingsMessage.snapshot(sender));
                return;
            }
            if ("npc_cull".equals(this.action)) {
                difficulty.setPlayerCullEnabled(sender.getUUID(), this.valueA != 0.0D);
                difficulty.setPlayerCullDistance(sender.getUUID(), (int) Math.round(this.valueB));
                if (this.valueB > 0.0D) {
                    difficulty.setPlayerViewDistance(sender.getUUID(), (int) Math.round(this.valueB));
                }
                com.npctacz.event.NpcAdminDefaults.save(sender.getServer());
                NpcNetwork.sendAdminSettings(sender, AdminSettingsMessage.snapshot(sender));
                return;
            }
            if (!sender.hasPermissions(2)) {
                return;
            }
            NpcFaction[] factions = NpcFaction.values();
            switch (this.action) {
                case "damage" -> difficulty.setGlobalDamageMultiplier(this.valueA);
                case "firerate" -> difficulty.setGlobalFireRateMultiplier(this.valueA);
                case "reaction" -> difficulty.setReactionTimeMultiplier(this.valueA);
                case "health" -> {
                    difficulty.setGlobalHealthMultiplier(this.valueA);
                    refreshAllNpcs(sender);
                }
                case "move_speed" -> {
                    difficulty.setGlobalMovementSpeedMultiplier(this.valueA);
                    refreshAllNpcs(sender);
                }
                case "soldier_hostile" -> difficulty.setSoldierHostilePlayers(this.valueA != 0.0D);
                case "melee_taken" -> difficulty.setMeleeDamageTakenMultiplier(this.valueA);
                case "headshot_bonus" -> difficulty.setPlayerHeadshotBonus(this.valueA);
                case "spread_coeff" -> difficulty.setGlobalSpreadMultiplier(this.valueA);
                case "range_all" -> difficulty.setGlobalRangeMultiplier(this.valueA);
                case "faction_range" -> {
                    if (this.faction >= 0 && this.faction < factions.length) {
                        difficulty.setFactionRangeMultiplier(factions[this.faction], this.valueA);
                    }
                }
                case "faction_spread_range" -> {
                    if (this.faction >= 0 && this.faction < factions.length) {
                        difficulty.setFactionSpreadMultiplier(factions[this.faction], this.valueA);
                        difficulty.setFactionRangeMultiplier(factions[this.faction], this.valueB);
                    }
                }
                case "faction_stats" -> {
                    if (this.faction >= 0 && this.faction < factions.length) {
                        difficulty.setFactionHealthMultiplier(factions[this.faction], this.valueA);
                        difficulty.setFactionDamageMultiplier(factions[this.faction], this.valueB);
                        refreshAllNpcs(sender);
                    }
                }
                case "clean_corpses" -> cleanCorpses(sender);
                case "clean_items" -> cleanItems(sender);
                case "refill_ammo" -> refillNearbyAmmo(sender);
                case "wake_npcs" -> wakeNpcs(sender);
                case "difficulty" -> difficulty.setDifficulty((int) Math.round(this.valueA));
                case "drop_guns" -> difficulty.setDropGuns(this.valueA != 0.0D);
                case "drop_ammo" -> difficulty.setDropAmmo(this.valueA != 0.0D);
                case "ammo_multiplier" -> difficulty.setAmmoDropMultiplier(this.valueA);
                case "max_npcs" -> difficulty.setMaxNpcs((int) Math.round(this.valueA));
                case "spawn_enabled" -> difficulty.setSpawnEnabled(this.valueA != 0.0D);
                case "spawn_prompt" -> difficulty.setGeneratePrompt(this.valueA != 0.0D);
                case "reset_defaults" -> {
                    difficulty.setGlobalDamageMultiplier(1.0D);
                    difficulty.setGlobalFireRateMultiplier(1.0D);
                    difficulty.setReactionTimeMultiplier(1.0D);
                    difficulty.setGlobalHealthMultiplier(1.0D);
                    difficulty.setGlobalMovementSpeedMultiplier(1.0D);
                    difficulty.setPlayerHeadshotBonus(6.5D);
                    difficulty.setSoldierHostilePlayers(false);
                    difficulty.resetFactionStats();
                    difficulty.resetRangeStats();
                    difficulty.resetPlayerViewDistances();
                    difficulty.setAmmoDropMultiplier(1.0D);
                    difficulty.setDropGuns(true);
                    difficulty.setDropAmmo(true);
                    difficulty.setSpawnEnabled(true);
                    difficulty.setGeneratePrompt(true);
                    difficulty.setMaxNpcs(32);
                    difficulty.setDifficulty(2);
                    spawn.resetDefaults();
                }
                case "spawn_amount" -> {
                    if (this.faction >= 0 && this.faction < factions.length) {
                        int min = (int) Math.round(this.valueA);
                        int max = (int) Math.round(this.valueB);
                        spawn.setAmount(factions[this.faction], Math.max(0, Math.min(min, 32)),
                            Math.max(0, Math.min(max, 32)));
                    }
                }
                case "spawn_faction_enabled" -> {
                    if (this.faction >= 0 && this.faction < factions.length) {
                        spawn.setEnabled(factions[this.faction], this.valueA != 0.0D);
                    }
                }
                case "spawn_interval" -> {
                    if (this.faction >= 0 && this.faction < factions.length) {
                        long ticks = Math.max(20L, Math.round(this.valueA));
                        spawn.setInterval(factions[this.faction], ticks);
                    }
                }
                case "spawn_interval_all" -> {
                    long ticks = Math.max(20L, Math.round(this.valueA));
                    for (NpcFaction faction : factions) {
                        spawn.setInterval(faction, ticks);
                    }
                }
                default -> {
                    return;
                }
            }
            com.npctacz.event.NpcAdminDefaults.save(sender.getServer());
            if (this.action.startsWith("spawn_") || this.action.equals("reset_defaults")) {
                com.npctacz.event.NpcSpawnEvents.onConfigChanged();
            }
            NpcNetwork.sendAdminSettings(sender, AdminSettingsMessage.snapshot(sender));
        });
        context.setPacketHandled(true);
    }

    private static void refreshAllNpcs(ServerPlayer sender) {
        for (var level : sender.server.getAllLevels()) {
            for (com.npctacz.entity.WastelandNpcEntity npc
                : level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class,
                    new net.minecraft.world.phys.AABB(-3.0E7, -1024, -3.0E7, 3.0E7, 1024, 3.0E7))) {
                npc.refreshAdminAttributes(false);
            }
        }
    }

    private static void cleanCorpses(ServerPlayer sender) {
        int removed = 0;
        for (var level : sender.server.getAllLevels()) {
            for (com.npctacz.entity.WastelandNpcEntity npc
                : level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class,
                    new net.minecraft.world.phys.AABB(-3.0E7, -1024, -3.0E7, 3.0E7, 1024, 3.0E7))) {
                if (npc.isRemoved() || npc.isDeadOrDying() || npc.getHealth() <= 0.0F) {
                    npc.discard();
                    removed++;
                }
            }
        }
        sender.displayClientMessage(net.minecraft.network.chat.Component.literal(
            "§a已清理尸体/幽灵 NPC：" + removed), true);
    }

    private static void wakeNpcs(ServerPlayer sender) {
        int woken = 0;
        var level = sender.serverLevel();
        for (com.npctacz.entity.WastelandNpcEntity npc
            : level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class,
                sender.getBoundingBox().inflate(64.0D))) {
            npc.wakeUp();
            woken++;
        }
        sender.displayClientMessage(net.minecraft.network.chat.Component.literal(
            "§a已唤醒附近 NPC：" + woken), true);
    }

    private static void cleanItems(ServerPlayer sender) {
        int removed = 0;
        for (var level : sender.server.getAllLevels()) {
            for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                sender.getBoundingBox().inflate(64.0D))) {
                item.discard();
                removed++;
            }
        }
        sender.displayClientMessage(net.minecraft.network.chat.Component.literal(
            "§a已清理附近掉落物：" + removed), true);
    }

    private static void refillNearbyAmmo(ServerPlayer sender) {
        int refilled = 0;
        var level = sender.serverLevel();
        for (var npc : level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class,
            sender.getBoundingBox().inflate(64.0D))) {
            npc.ammo().setReserve(npc.getMaxAmmo());
            refilled++;
        }
        sender.displayClientMessage(net.minecraft.network.chat.Component.literal(
            "§a已补满附近 NPC 备弹：" + refilled), true);
    }
}
