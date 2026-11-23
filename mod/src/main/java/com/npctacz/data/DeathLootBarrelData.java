package com.npctacz.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 追踪 NPC 死亡后自动生成的桶，并在 20 分钟后清除。
 *
 * <p>玩家手动破坏桶后会取消追踪，因此玩家自己放下的桶不会被自动清除。</p>
 */
public final class DeathLootBarrelData extends SavedData {

    private static final String DATA_NAME = "npc_tacz_death_barrels";
    /** 20 分钟 = 20 * 60 * 20 tick。 */
    public static final long LIFETIME_TICKS = 20L * 60L * 20L;

    private record BarrelKey(String dimension, long packedPos) {
    }

    private final Map<BarrelKey, Long> expiries = new HashMap<>();

    public static DeathLootBarrelData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            DeathLootBarrelData::load,
            DeathLootBarrelData::new,
            DATA_NAME
        );
    }

    private DeathLootBarrelData() {
    }

    public void track(ServerLevel level, BlockPos pos, long expiryTick) {
        this.expiries.put(new BarrelKey(level.dimension().location().toString(), pos.asLong()), expiryTick);
        this.setDirty();
    }

    public void untrack(ServerLevel level, BlockPos pos) {
        BarrelKey key = new BarrelKey(level.dimension().location().toString(), pos.asLong());
        if (this.expiries.remove(key) != null) {
            this.setDirty();
        }
    }

    /** 停止自动清理现有桶（关闭自动消失时调用，不删除桶本身）。 */
    public void clearTracking() {
        if (!this.expiries.isEmpty()) {
            this.expiries.clear();
            this.setDirty();
        }
    }

    /** 清除玩家附近当前维度的自动战利品桶；返回清除数量。 */
    public int clearNear(ServerLevel origin, BlockPos center, double radius) {
        double radiusSqr = radius * radius;
        String dimension = origin.dimension().location().toString();
        int removed = 0;
        Iterator<Map.Entry<BarrelKey, Long>> iterator = this.expiries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BarrelKey, Long> entry = iterator.next();
            BarrelKey key = entry.getKey();
            if (!key.dimension().equals(dimension)) {
                continue;
            }
            BlockPos pos = BlockPos.of(key.packedPos());
            if (pos.distSqr(center) > radiusSqr || !origin.hasChunkAt(pos)) {
                continue;
            }
            if (origin.getBlockState(pos).is(Blocks.BARREL)) {
                origin.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            iterator.remove();
            removed++;
        }
        if (removed > 0) {
            this.setDirty();
        }
        return removed;
    }

    /** 每 20 tick 调用一次。只处理已经加载的区块，避免强加载。 */
    public void tick(MinecraftServer server) {
        if (this.expiries.isEmpty()) {
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        long now = overworld.getGameTime();
        boolean changed = false;
        Iterator<Map.Entry<BarrelKey, Long>> iterator = this.expiries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BarrelKey, Long> entry = iterator.next();
            if (now < entry.getValue()) {
                continue;
            }
            BarrelKey key = entry.getKey();
            ServerLevel level = server.getLevel(ResourceKey.create(
                Registries.DIMENSION,
                new ResourceLocation(key.dimension())
            ));
            if (level == null) {
                iterator.remove();
                changed = true;
                continue;
            }
            BlockPos pos = BlockPos.of(key.packedPos());
            if (!level.hasChunkAt(pos)) {
                // 区块没加载：先留着，加载后再处理。
                continue;
            }
            if (level.getBlockState(pos).is(Blocks.BARREL)) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            iterator.remove();
            changed = true;
        }
        if (changed) {
            this.setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<BarrelKey, Long> entry : this.expiries.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putString("Dimension", entry.getKey().dimension());
            item.putLong("Pos", entry.getKey().packedPos());
            item.putLong("Expiry", entry.getValue());
            list.add(item);
        }
        tag.put("Barrels", list);
        return tag;
    }

    private static DeathLootBarrelData load(CompoundTag tag) {
        DeathLootBarrelData data = new DeathLootBarrelData();
        ListTag list = tag.getList("Barrels", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag item = list.getCompound(i);
            String dimension = item.getString("Dimension");
            if (dimension.isEmpty()) {
                continue;
            }
            data.expiries.put(
                new BarrelKey(dimension, item.getLong("Pos")),
                item.getLong("Expiry")
            );
        }
        return data;
    }
}
