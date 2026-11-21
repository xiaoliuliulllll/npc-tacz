package com.npctacz.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world NPC difficulty. Defaults to 2 (normal).
 * 1 = easy, 2 = normal, 3 = hard, 4 = nightmare.
 */
public final class NpcDifficultyData extends SavedData {
    private static final String DATA_NAME = "npc_tacz_difficulty";
    private static final int DEFAULT_DIFFICULTY = 2;

    private int difficulty = DEFAULT_DIFFICULTY;
    /** 全服 NPC 总数上限（独立于原版生物上限），默认 32。 */
    private int maxNpcs = 32;
    /** 自然生成 NPC 时是否给玩家发提示，默认开启。 */
    private boolean generatePrompt = true;

    // ------------------------------------------------------------------ 管理员界面（Alt+Shift+N）
    /** 全局 NPC 伤害倍率（默认 1）。 */
    private double globalDamageMultiplier = 1.0D;
    /** 全局 NPC 射速倍率（默认 1）。 */
    private double globalFireRateMultiplier = 1.0D;
    /** NPC 死亡是否掉落枪械。 */
    private boolean dropGuns = true;
    /** NPC 死亡是否掉落子弹。 */
    private boolean dropAmmo = true;
    /** 掉落子弹数量倍率（默认 1）。 */
    private double ammoDropMultiplier = 1.0D;
    /** 自然生成总开关。 */
    private boolean spawnEnabled = true;
    /** 全局反应时间倍率（默认 1；越小反应越快）。 */
    private double reactionTimeMultiplier = 1.0D;
    /** 全局 NPC 最大生命倍率（默认 1）。 */
    private double globalHealthMultiplier = 1.0D;
    /** 全局 NPC 移动速度倍率（默认 1；相对 0.09 基础速度）。 */
    private double globalMovementSpeedMultiplier = 1.0D;
    /** 士兵是否主动与玩家敌对（默认 false；玩家先动手时仍会反击）。 */
    private boolean soldierHostilePlayers = false;
    /** NPC 受到近战伤害的全局倍率（默认 1.5）。 */
    private double meleeDamageTakenMultiplier = 1.5D;
    /** 每个阵营单独的生命倍率（默认 1）。 */
    private final java.util.Map<com.npctacz.entity.NpcFaction, Double> factionHealthMultipliers =
        new java.util.EnumMap<>(com.npctacz.entity.NpcFaction.class);
    /** 每个阵营单独的伤害倍率（默认 1）。 */
    private final java.util.Map<com.npctacz.entity.NpcFaction, Double> factionDamageMultipliers =
        new java.util.EnumMap<>(com.npctacz.entity.NpcFaction.class);
    /** NPC 全局散布系数（默认 1；同时作用于静止/移动）。 */
    private double globalSpreadMultiplier = 1.0D;
    /** NPC 全局射程系数（默认 1）。 */
    private double globalRangeMultiplier = 1.0D;
    /** 每个阵营单独的射程系数（默认 1）。 */
    private final java.util.Map<com.npctacz.entity.NpcFaction, Double> factionRangeMultipliers =
        new java.util.EnumMap<>(com.npctacz.entity.NpcFaction.class);
    /** 每个阵营单独的扩散系数（默认 1）。 */
    private final java.util.Map<com.npctacz.entity.NpcFaction, Double> factionSpreadMultipliers =
        new java.util.EnumMap<>(com.npctacz.entity.NpcFaction.class);

    /** 每个玩家自己的 NPC 可见/受击距离，默认 96；范围 32~10240。 */
    public static final int DEFAULT_VIEW_DISTANCE = 96;
    public static final int MIN_VIEW_DISTANCE = 32;
    public static final int MAX_VIEW_DISTANCE = 10240;
    private final java.util.Map<java.util.UUID, Integer> playerViewDistances = new java.util.HashMap<>();
    /** NPC 剔除开关/距离：开启后超过距离既不显示也不攻击，范围 32~1024。 */
    public static final int DEFAULT_CULL_DISTANCE = 96;
    public static final int MIN_CULL_DISTANCE = 32;
    public static final int MAX_CULL_DISTANCE = 1024;
    private final java.util.Map<java.util.UUID, Integer> playerCullDistances = new java.util.HashMap<>();
    private final java.util.Set<java.util.UUID> playerCullEnabled = new java.util.HashSet<>();
    /** 每个玩家的聊天栏提示开关；默认开启。 */
    private final java.util.Set<java.util.UUID> chatNotificationsDisabled = new java.util.HashSet<>();
    /** 玩家爆头 NPC 的额外固定伤害（默认 6.5，0~2147483647）。 */
    private double playerHeadshotBonus = 6.5D;

    public static NpcDifficultyData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
            NpcDifficultyData::load,
            NpcDifficultyData::new,
            DATA_NAME
        );
    }

    private static NpcDifficultyData load(CompoundTag tag) {
        NpcDifficultyData data = new NpcDifficultyData();
        data.difficulty = Math.max(1, Math.min(4, tag.getInt("Difficulty")));
        if (tag.contains("MaxNpcs")) {
            data.maxNpcs = Math.max(1, tag.getInt("MaxNpcs"));
        } else {
            data.maxNpcs = Math.max(1, com.npctacz.config.NpcGlobalConfig.getInt("npc_max_default", 32));
        }
        if (data.difficulty == 0) {
            data.difficulty = DEFAULT_DIFFICULTY;
        }
        // 老存档没有这个键，默认开启
        data.generatePrompt = !tag.contains("GeneratePrompt") || tag.getBoolean("GeneratePrompt");
        // 管理员界面字段：老存档没有就用默认值
        data.globalDamageMultiplier = tag.contains("GlobalDamageMultiplier")
            ? Math.max(0.1D, Math.min(10.0D, tag.getDouble("GlobalDamageMultiplier"))) : 1.0D;
        data.globalFireRateMultiplier = tag.contains("GlobalFireRateMultiplier")
            ? Math.max(0.1D, Math.min(5.0D, tag.getDouble("GlobalFireRateMultiplier"))) : 1.0D;
        data.dropGuns = !tag.contains("DropGuns") || tag.getBoolean("DropGuns");
        data.dropAmmo = !tag.contains("DropAmmo") || tag.getBoolean("DropAmmo");
        data.ammoDropMultiplier = tag.contains("AmmoDropMultiplier")
            ? Math.max(0.0D, Math.min(5.0D, tag.getDouble("AmmoDropMultiplier"))) : 1.0D;
        data.spawnEnabled = !tag.contains("SpawnEnabled") || tag.getBoolean("SpawnEnabled");
        data.reactionTimeMultiplier = tag.contains("ReactionTimeMultiplier")
            ? Math.max(0.1D, Math.min(5.0D, tag.getDouble("ReactionTimeMultiplier"))) : 1.0D;
        data.globalHealthMultiplier = tag.contains("GlobalHealthMultiplier")
            ? Math.max(0.1D, Math.min(10.0D, tag.getDouble("GlobalHealthMultiplier"))) : 1.0D;
        data.globalMovementSpeedMultiplier = tag.contains("GlobalMovementSpeedMultiplier")
            ? Math.max(0.1D, Math.min(5.0D, tag.getDouble("GlobalMovementSpeedMultiplier"))) : 1.0D;
        data.soldierHostilePlayers = tag.getBoolean("SoldierHostilePlayers");
        data.playerHeadshotBonus = tag.contains("PlayerHeadshotBonus")
            ? Math.max(0.0D, Math.min(2147483647.0D, tag.getDouble("PlayerHeadshotBonus")))
            : 6.5D;
        data.globalSpreadMultiplier = tag.contains("GlobalSpreadMultiplier")
            ? Math.max(0.1D, Math.min(10.0D, tag.getDouble("GlobalSpreadMultiplier"))) : 1.0D;
        data.globalRangeMultiplier = tag.contains("GlobalRangeMultiplier")
            ? Math.max(0.1D, Math.min(5.0D, tag.getDouble("GlobalRangeMultiplier"))) : 1.0D;
        if (tag.contains("FactionSpreadMultipliers")) {
            var compound = tag.getCompound("FactionSpreadMultipliers");
            for (var faction : com.npctacz.entity.NpcFaction.values()) {
                if (compound.contains(faction.name())) {
                    data.factionSpreadMultipliers.put(faction,
                        Math.max(0.1D, Math.min(10.0D, compound.getDouble(faction.name()))));
                }
            }
        }
        if (tag.contains("FactionRangeMultipliers")) {
            var compound = tag.getCompound("FactionRangeMultipliers");
            for (var faction : com.npctacz.entity.NpcFaction.values()) {
                if (compound.contains(faction.name())) {
                    data.factionRangeMultipliers.put(faction,
                        Math.max(0.1D, Math.min(5.0D, compound.getDouble(faction.name()))));
                }
            }
        }
        data.meleeDamageTakenMultiplier = tag.contains("MeleeDamageTakenMultiplier")
            ? Math.max(0.1D, Math.min(10.0D, tag.getDouble("MeleeDamageTakenMultiplier"))) : 1.5D;
        if (tag.contains("FactionHealthMultipliers")) {
            var compound = tag.getCompound("FactionHealthMultipliers");
            for (var faction : com.npctacz.entity.NpcFaction.values()) {
                if (compound.contains(faction.name())) {
                    data.factionHealthMultipliers.put(faction,
                        Math.max(0.1D, Math.min(10.0D, compound.getDouble(faction.name()))));
                }
            }
        }
        if (tag.contains("FactionDamageMultipliers")) {
            var compound = tag.getCompound("FactionDamageMultipliers");
            for (var faction : com.npctacz.entity.NpcFaction.values()) {
                if (compound.contains(faction.name())) {
                    data.factionDamageMultipliers.put(faction,
                        Math.max(0.1D, Math.min(10.0D, compound.getDouble(faction.name()))));
                }
            }
        }
        if (tag.contains("ChatNotificationsDisabled")) {
            var list = tag.getList("ChatNotificationsDisabled", 10);
            for (int i = 0; i < list.size(); i++) {
                try {
                    data.chatNotificationsDisabled.add(
                        java.util.UUID.fromString(list.getCompound(i).getString("UUID")));
                } catch (Throwable ignored) {
                }
            }
        }
        if (tag.contains("PlayerCullDistances")) {
            var list = tag.getList("PlayerCullDistances", 10);
            for (int i = 0; i < list.size(); i++) {
                var entry = list.getCompound(i);
                try {
                    data.playerCullDistances.put(java.util.UUID.fromString(entry.getString("UUID")),
                        Math.max(MIN_CULL_DISTANCE, Math.min(MAX_CULL_DISTANCE, entry.getInt("Distance"))));
                    if (entry.getBoolean("Enabled")) {
                        data.playerCullEnabled.add(java.util.UUID.fromString(entry.getString("UUID")));
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (tag.contains("PlayerViewDistances")) {
            var list = tag.getList("PlayerViewDistances", 10);
            for (int i = 0; i < list.size(); i++) {
                var entry = list.getCompound(i);
                try {
                    java.util.UUID id = java.util.UUID.fromString(entry.getString("UUID"));
                    int distance = Math.max(MIN_VIEW_DISTANCE,
                        Math.min(MAX_VIEW_DISTANCE, entry.getInt("Distance")));
                    data.playerViewDistances.put(id, distance);
                } catch (Throwable ignored) {
                    // 忽略损坏条目
                }
            }
        }
        return data;
    }

    public int getDifficulty() {
        return difficulty;
    }

    public boolean isGeneratePrompt() {
        return this.generatePrompt;
    }

    public void setGeneratePrompt(boolean generatePrompt) {
        this.generatePrompt = generatePrompt;
        this.setDirty();
    }

    public int getMaxNpcs() {
        return this.maxNpcs;
    }

    public void setMaxNpcs(int maxNpcs) {
        this.maxNpcs = Math.max(1, maxNpcs);
        this.setDirty();
    }

    public void setDifficulty(int difficulty) {
        this.difficulty = Math.max(1, Math.min(4, difficulty));
        this.setDirty();
    }

    public double getGlobalDamageMultiplier() {
        return this.globalDamageMultiplier;
    }

    public void setGlobalDamageMultiplier(double value) {
        this.globalDamageMultiplier = Math.max(0.1D, Math.min(10.0D, value));
        this.setDirty();
    }

    public double getGlobalFireRateMultiplier() {
        return this.globalFireRateMultiplier;
    }

    public void setGlobalFireRateMultiplier(double value) {
        this.globalFireRateMultiplier = Math.max(0.1D, Math.min(5.0D, value));
        this.setDirty();
    }

    public boolean isDropGuns() {
        return this.dropGuns;
    }

    public void setDropGuns(boolean value) {
        this.dropGuns = value;
        this.setDirty();
    }

    public boolean isDropAmmo() {
        return this.dropAmmo;
    }

    public void setDropAmmo(boolean value) {
        this.dropAmmo = value;
        this.setDirty();
    }

    public double getAmmoDropMultiplier() {
        return this.ammoDropMultiplier;
    }

    public void setAmmoDropMultiplier(double value) {
        this.ammoDropMultiplier = Math.max(0.0D, Math.min(5.0D, value));
        this.setDirty();
    }

    public boolean isSpawnEnabled() {
        return this.spawnEnabled;
    }

    public void setSpawnEnabled(boolean value) {
        this.spawnEnabled = value;
        this.setDirty();
    }

    public double getReactionTimeMultiplier() {
        return this.reactionTimeMultiplier;
    }

    public void setReactionTimeMultiplier(double value) {
        this.reactionTimeMultiplier = Math.max(0.1D, Math.min(5.0D, value));
        this.setDirty();
    }

    public double getGlobalHealthMultiplier() {
        return this.globalHealthMultiplier;
    }

    public void setGlobalHealthMultiplier(double value) {
        this.globalHealthMultiplier = Math.max(0.1D, Math.min(10.0D, value));
        this.setDirty();
    }

    public double getGlobalMovementSpeedMultiplier() {
        return this.globalMovementSpeedMultiplier;
    }

    public void setGlobalMovementSpeedMultiplier(double value) {
        this.globalMovementSpeedMultiplier = Math.max(0.1D, Math.min(5.0D, value));
        this.setDirty();
    }

    public boolean isSoldierHostilePlayers() {
        return this.soldierHostilePlayers;
    }

    public void setSoldierHostilePlayers(boolean value) {
        this.soldierHostilePlayers = value;
        this.setDirty();
    }

    public double getMeleeDamageTakenMultiplier() {
        return this.meleeDamageTakenMultiplier;
    }

    public void setMeleeDamageTakenMultiplier(double value) {
        this.meleeDamageTakenMultiplier = Math.max(0.1D, Math.min(10.0D, value));
        this.setDirty();
    }

    public double getFactionHealthMultiplier(com.npctacz.entity.NpcFaction faction) {
        return faction == null ? 1.0D : this.factionHealthMultipliers.getOrDefault(faction, 1.0D);
    }

    public void setFactionHealthMultiplier(com.npctacz.entity.NpcFaction faction, double value) {
        if (faction != null) {
            this.factionHealthMultipliers.put(faction, Math.max(0.1D, Math.min(10.0D, value)));
            this.setDirty();
        }
    }

    public double getPlayerHeadshotBonus() {
        return this.playerHeadshotBonus;
    }

    public void setPlayerHeadshotBonus(double value) {
        this.playerHeadshotBonus = Math.max(0.0D, Math.min(2147483647.0D, value));
        this.setDirty();
    }

    public double getGlobalSpreadMultiplier() {
        return this.globalSpreadMultiplier;
    }

    public void setGlobalSpreadMultiplier(double value) {
        this.globalSpreadMultiplier = Math.max(0.1D, Math.min(10.0D, value));
        this.setDirty();
    }

    public double getGlobalRangeMultiplier() {
        return this.globalRangeMultiplier;
    }

    public void setGlobalRangeMultiplier(double value) {
        this.globalRangeMultiplier = Math.max(0.1D, Math.min(5.0D, value));
        this.setDirty();
    }

    public double getFactionSpreadMultiplier(com.npctacz.entity.NpcFaction faction) {
        return faction == null ? 1.0D : this.factionSpreadMultipliers.getOrDefault(faction, 1.0D);
    }

    public void setFactionSpreadMultiplier(com.npctacz.entity.NpcFaction faction, double value) {
        if (faction != null) {
            this.factionSpreadMultipliers.put(faction, Math.max(0.1D, Math.min(10.0D, value)));
            this.setDirty();
        }
    }

    public double getFactionRangeMultiplier(com.npctacz.entity.NpcFaction faction) {
        return faction == null ? 1.0D : this.factionRangeMultipliers.getOrDefault(faction, 1.0D);
    }

    public void setFactionRangeMultiplier(com.npctacz.entity.NpcFaction faction, double value) {
        if (faction != null) {
            this.factionRangeMultipliers.put(faction, Math.max(0.1D, Math.min(5.0D, value)));
            this.setDirty();
        }
    }

    public void resetRangeStats() {
        this.globalSpreadMultiplier = 1.0D;
        this.globalRangeMultiplier = 1.0D;
        this.factionRangeMultipliers.clear();
        this.factionSpreadMultipliers.clear();
        this.setDirty();
    }

    public double getFactionDamageMultiplier(com.npctacz.entity.NpcFaction faction) {
        return faction == null ? 1.0D : this.factionDamageMultipliers.getOrDefault(faction, 1.0D);
    }

    public void setFactionDamageMultiplier(com.npctacz.entity.NpcFaction faction, double value) {
        if (faction != null) {
            this.factionDamageMultipliers.put(faction, Math.max(0.1D, Math.min(10.0D, value)));
            this.setDirty();
        }
    }

    public void resetFactionStats() {
        this.factionHealthMultipliers.clear();
        this.factionDamageMultipliers.clear();
        this.meleeDamageTakenMultiplier = 1.5D;
        this.setDirty();
    }

    public int getPlayerViewDistance(java.util.UUID playerId) {
        if (playerId == null) {
            return DEFAULT_VIEW_DISTANCE;
        }
        return this.playerViewDistances.getOrDefault(playerId, DEFAULT_VIEW_DISTANCE);
    }

    public void setPlayerViewDistance(java.util.UUID playerId, int distance) {
        if (playerId == null) {
            return;
        }
        this.playerViewDistances.put(playerId, Math.max(MIN_VIEW_DISTANCE,
            Math.min(MAX_VIEW_DISTANCE, distance)));
        this.setDirty();
    }

    public java.util.Map<java.util.UUID, Integer> getPlayerViewDistances() {
        return this.playerViewDistances;
    }

    public boolean areChatNotificationsEnabled(java.util.UUID playerId) {
        return playerId == null || !this.chatNotificationsDisabled.contains(playerId);
    }

    public void setChatNotificationsEnabled(java.util.UUID playerId, boolean enabled) {
        if (playerId == null) return;
        if (enabled) this.chatNotificationsDisabled.remove(playerId);
        else this.chatNotificationsDisabled.add(playerId);
        this.setDirty();
    }

    public java.util.Set<java.util.UUID> getChatNotificationsDisabled() {
        return this.chatNotificationsDisabled;
    }

    public boolean isPlayerCullEnabled(java.util.UUID playerId) {
        return playerId != null && this.playerCullEnabled.contains(playerId);
    }

    public void setPlayerCullEnabled(java.util.UUID playerId, boolean enabled) {
        if (playerId == null) return;
        if (enabled) this.playerCullEnabled.add(playerId); else this.playerCullEnabled.remove(playerId);
        this.setDirty();
    }

    public int getPlayerCullDistance(java.util.UUID playerId) {
        return playerId == null ? DEFAULT_CULL_DISTANCE
            : this.playerCullDistances.getOrDefault(playerId, DEFAULT_CULL_DISTANCE);
    }

    public void setPlayerCullDistance(java.util.UUID playerId, int distance) {
        if (playerId == null) return;
        this.playerCullDistances.put(playerId, Math.max(MIN_CULL_DISTANCE,
            Math.min(MAX_CULL_DISTANCE, distance)));
        this.setDirty();
    }

    public java.util.Map<java.util.UUID, Integer> getPlayerCullDistances() {
        return this.playerCullDistances;
    }

    public java.util.Set<java.util.UUID> getPlayerCullEnabled() {
        return this.playerCullEnabled;
    }

    public void resetPlayerViewDistances() {
        this.playerCullDistances.clear();
        this.playerCullEnabled.clear();
        this.chatNotificationsDisabled.clear();
        this.playerViewDistances.clear();
        this.setDirty();
    }

    public float healthMultiplier() {
        return switch (difficulty) {
            case 1 -> 0.75F;
            case 3 -> 1.25F;
            case 4 -> 1.5F;
            default -> 1.0F;
        };
    }

    public float damageMultiplier() {
        return switch (difficulty) {
            case 1 -> 0.75F;
            case 3 -> 1.25F;
            case 4 -> 1.5F;
            default -> 1.0F;
        };
    }

    public float spawnMultiplier() {
        return switch (difficulty) {
            case 1 -> 0.75F;
            case 3 -> 1.25F;
            case 4 -> 1.5F;
            default -> 1.0F;
        };
    }

    public String displayName() {
        return switch (difficulty) {
            case 1 -> "简单";
            case 3 -> "困难";
            case 4 -> "噩梦";
            default -> "普通";
        };
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("Difficulty", difficulty);
        tag.putBoolean("GeneratePrompt", generatePrompt);
        tag.putInt("MaxNpcs", maxNpcs);
        tag.putDouble("GlobalDamageMultiplier", globalDamageMultiplier);
        tag.putDouble("GlobalFireRateMultiplier", globalFireRateMultiplier);
        tag.putBoolean("DropGuns", dropGuns);
        tag.putBoolean("DropAmmo", dropAmmo);
        tag.putDouble("AmmoDropMultiplier", ammoDropMultiplier);
        tag.putBoolean("SpawnEnabled", spawnEnabled);
        tag.putDouble("ReactionTimeMultiplier", reactionTimeMultiplier);
        tag.putDouble("GlobalHealthMultiplier", globalHealthMultiplier);
        tag.putDouble("GlobalMovementSpeedMultiplier", globalMovementSpeedMultiplier);
        tag.putBoolean("SoldierHostilePlayers", soldierHostilePlayers);
        tag.putDouble("PlayerHeadshotBonus", playerHeadshotBonus);
        tag.putDouble("MeleeDamageTakenMultiplier", meleeDamageTakenMultiplier);
        var factionHealth = new CompoundTag();
        for (var entry : this.factionHealthMultipliers.entrySet()) {
            factionHealth.putDouble(entry.getKey().name(), entry.getValue());
        }
        tag.put("FactionHealthMultipliers", factionHealth);
        var factionDamage = new CompoundTag();
        for (var entry : this.factionDamageMultipliers.entrySet()) {
            factionDamage.putDouble(entry.getKey().name(), entry.getValue());
        }
        tag.put("FactionDamageMultipliers", factionDamage);
        tag.putDouble("GlobalSpreadMultiplier", globalSpreadMultiplier);
        tag.putDouble("GlobalRangeMultiplier", globalRangeMultiplier);
        var factionRange = new CompoundTag();
        for (var entry : this.factionRangeMultipliers.entrySet()) {
            factionRange.putDouble(entry.getKey().name(), entry.getValue());
        }
        tag.put("FactionRangeMultipliers", factionRange);
        var factionSpread = new CompoundTag();
        for (var entry : this.factionSpreadMultipliers.entrySet()) {
            factionSpread.putDouble(entry.getKey().name(), entry.getValue());
        }
        tag.put("FactionSpreadMultipliers", factionSpread);
        var viewList = new net.minecraft.nbt.ListTag();
        for (var entry : this.playerViewDistances.entrySet()) {
            var viewTag = new CompoundTag();
            viewTag.putString("UUID", entry.getKey().toString());
            viewTag.putInt("Distance", entry.getValue());
            viewList.add(viewTag);
        }
        tag.put("PlayerViewDistances", viewList);
        var cullList = new net.minecraft.nbt.ListTag();
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>(this.playerCullDistances.keySet());
        ids.addAll(this.playerCullEnabled);
        for (var id : ids) {
            var cullTag = new CompoundTag();
            cullTag.putString("UUID", id.toString());
            cullTag.putInt("Distance", this.getPlayerCullDistance(id));
            cullTag.putBoolean("Enabled", this.playerCullEnabled.contains(id));
            cullList.add(cullTag);
        }
        tag.put("PlayerCullDistances", cullList);
        var chatList = new net.minecraft.nbt.ListTag();
        for (var id : this.chatNotificationsDisabled) {
            var chatTag = new CompoundTag();
            chatTag.putString("UUID", id.toString());
            chatList.add(chatTag);
        }
        tag.put("ChatNotificationsDisabled", chatList);
        return tag;
    }
}
