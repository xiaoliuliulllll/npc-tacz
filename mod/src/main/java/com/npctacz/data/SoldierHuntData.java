package com.npctacz.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-world soldier pursuit state.
 *
 * <p>A player who kills a soldier is marked for three in-game days. Killing more
 * soldiers during an active pursuit does not extend or stack it. At noon each
 * day a random subset of the soldiers that already exist at that moment receives
 * the player's position; soldiers spawned later do not get it until the next noon.</p>
 */
public final class SoldierHuntData extends SavedData {

    public static final long HUNT_DURATION_TICKS = 3L * 24_000L;
    private static final String DATA_NAME = "npc_tacz_soldier_hunt";

    private final Map<UUID, Long> expiryByPlayer = new HashMap<>();
    private long lastHuntNoonDay = -1L;

    public static SoldierHuntData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
            SoldierHuntData::load,
            SoldierHuntData::new,
            DATA_NAME
        );
    }

    private static SoldierHuntData load(CompoundTag tag) {
        SoldierHuntData data = new SoldierHuntData();
        data.lastHuntNoonDay = tag.getLong("LastHuntNoonDay");
        ListTag list = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("Id")) {
                data.expiryByPlayer.put(entry.getUUID("Id"), entry.getLong("Expiry"));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("LastHuntNoonDay", this.lastHuntNoonDay);
        ListTag list = new ListTag();
        this.expiryByPlayer.forEach((uuid, expiry) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", uuid);
            entry.putLong("Expiry", expiry);
            list.add(entry);
        });
        tag.put("Players", list);
        return tag;
    }

    /** Starts a pursuit only if this player does not already have one. */
    public boolean startHunt(Player player, long gameTime) {
        if (this.isHunting(player, gameTime)) {
            return false;
        }
        this.expiryByPlayer.put(player.getUUID(), gameTime + HUNT_DURATION_TICKS);
        this.setDirty();
        return true;
    }

    public boolean isHunting(Player player, long gameTime) {
        if (player == null) {
            return false;
        }
        Long expiry = this.expiryByPlayer.get(player.getUUID());
        return expiry != null && expiry > gameTime;
    }

    public boolean isHunting(UUID uuid, long gameTime) {
        Long expiry = this.expiryByPlayer.get(uuid);
        return expiry != null && expiry > gameTime;
    }

    /** Remove expired entries; safe to call every tick. */
    public void purge(long gameTime) {
        if (this.expiryByPlayer.entrySet().removeIf(entry -> entry.getValue() <= gameTime)) {
            this.setDirty();
        }
    }

    /** @return true only once per in-game day, at the noon transition. */
    public boolean markHuntNoon(long day) {
        if (this.lastHuntNoonDay == day) {
            return false;
        }
        this.lastHuntNoonDay = day;
        this.setDirty();
        return true;
    }

    public Map<UUID, Long> activeHunts(long gameTime) {
        Map<UUID, Long> result = new HashMap<>();
        this.expiryByPlayer.forEach((uuid, expiry) -> {
            if (expiry > gameTime) {
                result.put(uuid, expiry);
            }
        });
        return result;
    }
}
