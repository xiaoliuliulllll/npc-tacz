package com.npctacz.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.npctacz.ModRegistry;
import com.npctacz.data.NpcDifficultyData;
import com.npctacz.data.NpcSpawnConfigData;
import com.npctacz.entity.NpcFaction;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 管理员界面的全局默认值。
 *
 * <p>Alt+N 的改动不只写当前世界存档，还会写到 config/tacz-npc-admin.json；
 * 服务器启动/新建存档时自动套用，因此换存档、开新世界也会保留同一套设置。</p>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcAdminDefaults {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("tacz-npc-admin.json");

    private NpcAdminDefaults() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        apply(event.getServer());
        // 首次启动也把当前世界的设置固化成全局默认，之后新存档直接继承
        save(event.getServer());
    }

    public static synchronized void save(MinecraftServer server) {
        if (server == null || server.overworld() == null) {
            return;
        }
        try {
            NpcDifficultyData difficulty = NpcDifficultyData.get(server.overworld());
            NpcSpawnConfigData spawn = NpcSpawnConfigData.get(server.overworld());
            JsonObject root = new JsonObject();
            root.addProperty("damage", difficulty.getGlobalDamageMultiplier());
            root.addProperty("firerate", difficulty.getGlobalFireRateMultiplier());
            root.addProperty("reaction", difficulty.getReactionTimeMultiplier());
            root.addProperty("health", difficulty.getGlobalHealthMultiplier());
            root.addProperty("movespeed", difficulty.getGlobalMovementSpeedMultiplier());
            root.addProperty("difficulty", difficulty.getDifficulty());
            root.addProperty("maxNpcs", difficulty.getMaxNpcs());
            root.addProperty("dropGuns", difficulty.isDropGuns());
            root.addProperty("dropAmmo", difficulty.isDropAmmo());
            root.addProperty("ammoMultiplier", difficulty.getAmmoDropMultiplier());
            root.addProperty("spawnEnabled", difficulty.isSpawnEnabled());
            root.addProperty("generatePrompt", difficulty.isGeneratePrompt());
            root.addProperty("soldierHostile", difficulty.isSoldierHostilePlayers());
            root.addProperty("meleeTaken", difficulty.getMeleeDamageTakenMultiplier());
            root.addProperty("playerHeadshotBonus", difficulty.getPlayerHeadshotBonus());
            root.addProperty("spreadMultiplier", difficulty.getGlobalSpreadMultiplier());
            root.addProperty("rangeMultiplier", difficulty.getGlobalRangeMultiplier());
            JsonObject factionRange = new JsonObject();
            JsonObject factionSpread = new JsonObject();
            for (NpcFaction faction : NpcFaction.values()) {
                factionRange.addProperty(faction.name(), difficulty.getFactionRangeMultiplier(faction));
                factionSpread.addProperty(faction.name(), difficulty.getFactionSpreadMultiplier(faction));
            }
            root.add("factionRange", factionRange);
            root.add("factionSpread", factionSpread);
            JsonObject factionHealth = new JsonObject();
            JsonObject factionDamage = new JsonObject();
            for (NpcFaction faction : NpcFaction.values()) {
                factionHealth.addProperty(faction.name(), difficulty.getFactionHealthMultiplier(faction));
                factionDamage.addProperty(faction.name(), difficulty.getFactionDamageMultiplier(faction));
            }
            root.add("factionHealth", factionHealth);
            root.add("factionDamage", factionDamage);

            JsonObject factions = new JsonObject();
            for (NpcFaction faction : NpcFaction.values()) {
                NpcSpawnConfigData.Rule rule = spawn.rule(faction);
                JsonObject entry = new JsonObject();
                entry.addProperty("min", rule.amountMin);
                entry.addProperty("max", rule.amountMax);
                entry.addProperty("interval", rule.intervalTicks);
                entry.addProperty("enabled", rule.enabled);
                factions.add(faction.name(), entry);
            }
            root.add("factions", factions);

            JsonObject views = new JsonObject();
            for (Map.Entry<UUID, Integer> entry : difficulty.getPlayerViewDistances().entrySet()) {
                views.addProperty(entry.getKey().toString(), entry.getValue());
            }
            root.add("viewDistances", views);
            JsonObject cullDistances = new JsonObject();
            JsonObject cullEnabled = new JsonObject();
            java.util.Set<java.util.UUID> cullIds = new java.util.HashSet<>(
                difficulty.getPlayerCullDistances().keySet());
            cullIds.addAll(difficulty.getPlayerCullEnabled());
            for (java.util.UUID id : cullIds) {
                cullDistances.addProperty(id.toString(), difficulty.getPlayerCullDistance(id));
                cullEnabled.addProperty(id.toString(), difficulty.isPlayerCullEnabled(id));
            }
            root.add("cullDistances", cullDistances);
            root.add("cullEnabled", cullEnabled);
            JsonArray chatDisabled = new JsonArray();
            for (java.util.UUID id : difficulty.getChatNotificationsDisabled()) {
                chatDisabled.add(id.toString());
            }
            root.add("chatNotificationsDisabled", chatDisabled);

            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            LOGGER.warn("[npc-tacz] failed to save global admin defaults", throwable);
        }
    }

    public static synchronized void apply(MinecraftServer server) {
        if (server == null || server.overworld() == null) {
            return;
        }
        try {
            JsonObject root;
            if (Files.isRegularFile(FILE)) {
                root = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), JsonObject.class);
            } else {
                // 配置还没生成时，使用打进 jar 的默认管理员设置。
                root = loadBundledDefaults();
            }
            if (root == null) {
                return;
            }
            NpcDifficultyData difficulty = NpcDifficultyData.get(server.overworld());
            difficulty.setGlobalDamageMultiplier(getDouble(root, "damage", 1.0D));
            difficulty.setGlobalFireRateMultiplier(getDouble(root, "firerate", 1.0D));
            difficulty.setReactionTimeMultiplier(getDouble(root, "reaction", 1.0D));
            difficulty.setGlobalHealthMultiplier(getDouble(root, "health", 1.0D));
            difficulty.setGlobalMovementSpeedMultiplier(getDouble(root, "movespeed", 1.0D));
            difficulty.setDifficulty(getInt(root, "difficulty", 2));
            difficulty.setMaxNpcs(getInt(root, "maxNpcs", 32));
            difficulty.setDropGuns(getBool(root, "dropGuns", true));
            difficulty.setDropAmmo(getBool(root, "dropAmmo", true));
            difficulty.setAmmoDropMultiplier(getDouble(root, "ammoMultiplier", 1.0D));
            difficulty.setSpawnEnabled(getBool(root, "spawnEnabled", true));
            difficulty.setGeneratePrompt(getBool(root, "generatePrompt", true));
            difficulty.setSoldierHostilePlayers(getBool(root, "soldierHostile", false));
            difficulty.setMeleeDamageTakenMultiplier(getDouble(root, "meleeTaken", 1.5D));
            difficulty.setPlayerHeadshotBonus(getDouble(root, "playerHeadshotBonus", 6.5D));
            difficulty.setGlobalSpreadMultiplier(getDouble(root, "spreadMultiplier", 1.0D));
            difficulty.setGlobalRangeMultiplier(getDouble(root, "rangeMultiplier", 1.0D));
            JsonObject factionRangeObj = root.has("factionRange")
                ? root.getAsJsonObject("factionRange") : new JsonObject();
            JsonObject factionSpreadObj = root.has("factionSpread")
                ? root.getAsJsonObject("factionSpread") : new JsonObject();
            for (NpcFaction faction : NpcFaction.values()) {
                difficulty.setFactionRangeMultiplier(faction,
                    getDouble(factionRangeObj, faction.name(), 1.0D));
                difficulty.setFactionSpreadMultiplier(faction,
                    getDouble(factionSpreadObj, faction.name(), 1.0D));
            }
            JsonObject factionHealth = root.has("factionHealth")
                ? root.getAsJsonObject("factionHealth") : new JsonObject();
            JsonObject factionDamage = root.has("factionDamage")
                ? root.getAsJsonObject("factionDamage") : new JsonObject();
            for (NpcFaction faction : NpcFaction.values()) {
                difficulty.setFactionHealthMultiplier(faction,
                    getDouble(factionHealth, faction.name(), 1.0D));
                difficulty.setFactionDamageMultiplier(faction,
                    getDouble(factionDamage, faction.name(), 1.0D));
            }

            NpcSpawnConfigData spawn = NpcSpawnConfigData.get(server.overworld());
            JsonObject factions = root.has("factions") ? root.getAsJsonObject("factions") : new JsonObject();
            for (NpcFaction faction : NpcFaction.values()) {
                if (!factions.has(faction.name())) {
                    continue;
                }
                JsonObject entry = factions.getAsJsonObject(faction.name());
                spawn.setAmount(faction, getInt(entry, "min", 1), getInt(entry, "max", 2));
                spawn.setInterval(faction, Math.max(20L, getInt(entry, "interval", 1200)));
                spawn.setEnabled(faction, getBool(entry, "enabled", true));
            }
            if (root.has("viewDistances")) {
                JsonObject views = root.getAsJsonObject("viewDistances");
                for (String key : views.keySet()) {
                    try {
                        difficulty.setPlayerViewDistance(UUID.fromString(key), views.get(key).getAsInt());
                    } catch (Throwable ignored) {
                        // 忽略损坏条目
                    }
                }
            }
            if (root.has("chatNotificationsDisabled")) {
                JsonArray disabled = root.getAsJsonArray("chatNotificationsDisabled");
                for (JsonElement element : disabled) {
                    try {
                        difficulty.setChatNotificationsEnabled(
                            UUID.fromString(element.getAsString()), false);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (root.has("cullDistances")) {
                JsonObject culls = root.getAsJsonObject("cullDistances");
                JsonObject enabled = root.has("cullEnabled")
                    ? root.getAsJsonObject("cullEnabled") : new JsonObject();
                for (String key : culls.keySet()) {
                    try {
                        java.util.UUID id = UUID.fromString(key);
                        difficulty.setPlayerCullDistance(id, culls.get(key).getAsInt());
                        difficulty.setPlayerCullEnabled(id,
                            enabled.has(key) && enabled.get(key).getAsBoolean());
                    } catch (Throwable ignored) {
                        // 忽略损坏条目
                    }
                }
            }
            LOGGER.info("[npc-tacz] applied global admin defaults from {}", FILE);
        } catch (Throwable throwable) {
            LOGGER.warn("[npc-tacz] failed to apply global admin defaults", throwable);
        }
    }

    private static JsonObject loadBundledDefaults() {
        try (InputStream input = NpcAdminDefaults.class.getResourceAsStream(
            "/assets/npc_tacz/default_admin_settings.json")) {
            if (input == null) {
                return null;
            }
            return GSON.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Throwable throwable) {
            LOGGER.warn("[npc-tacz] failed to load bundled admin defaults", throwable);
            return null;
        }
    }

    private static double getDouble(JsonObject object, String key, double fallback) {
        JsonElement value = object.get(key);
        return value == null ? fallback : value.getAsDouble();
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null ? fallback : value.getAsInt();
    }

    private static boolean getBool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value == null ? fallback : value.getAsBoolean();
    }
}
