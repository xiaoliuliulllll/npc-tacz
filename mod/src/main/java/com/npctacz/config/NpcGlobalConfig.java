package com.npctacz.config;

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * /config/tacz-npc-modif 全局修改文件。
 *
 * <p>格式（参考默认模板）：</p>
 * <pre>
 * // 代码：target_creative_players
 * // 中文：是否把创造模式玩家当作目标
 * target_creative_players : false
 * </pre>
 *
 * <p>支持 {@code //} 注释、空行、ASCII/中文冒号；值可以是 true/false、整数、小数、字符串。
 * 修改后执行 {@code /tacznpc reloadconfig} 立即重新读取。</p>
 */
public final class NpcGlobalConfig {

    public static final String FILE_NAME = "tacz-npc-modif";

    private static final Map<String, String> VALUES = new LinkedHashMap<>();
    private static volatile boolean loaded;
    private static volatile String lastError = "";

    private NpcGlobalConfig() {
    }

    public static synchronized void load() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get();
            Files.createDirectories(dir);
            Path file = dir.resolve(FILE_NAME);
            if (!Files.isRegularFile(file)) {
                Files.writeString(file, defaultTemplate(), StandardCharsets.UTF_8);
            }
            VALUES.clear();
            lastError = "";
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                    continue;
                }
                // 同时兼容中文冒号
                line = line.replace('：', ':');
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
                String value = line.substring(separator + 1).strip();
                if (key.isEmpty()) {
                    continue;
                }
                VALUES.put(key, value);
            }
            loaded = true;
            migrateLegacyDefaults(file);
        } catch (Throwable throwable) {
            loaded = true;
            lastError = String.valueOf(throwable);
        }
    }

    /**
     * 旧版本默认值迁移：旧模板里 weak_run_distance 默认 96，新版默认 128。
     * 只在 config_version < 2 且值仍是旧默认 96 时改，避免覆盖玩家自定义值。
     */
    private static void migrateLegacyDefaults(Path file) {
        try {
            if (getInt("config_version", 1) >= 2) {
                return;
            }
            Map<String, String> updates = new LinkedHashMap<>();
            if (getInt("weak_run_distance", 128) == 96) {
                updates.put("weak_run_distance", "128");
            }
            updates.put("config_version", "2");
            if (!updates.isEmpty()) {
                rewriteFile(file, updates);
                VALUES.putAll(updates);
            }
        } catch (Throwable throwable) {
            lastError = String.valueOf(throwable);
        }
    }

    /** 修改单个配置值并写回文件（保留注释/其它行）。 */
    public static synchronized boolean setAndSave(String key, String value) {
        try {
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            Path file = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            Map<String, String> updates = new LinkedHashMap<>();
            updates.put(normalizedKey, value);
            if (getInt("config_version", 2) < 2) {
                updates.put("config_version", "2");
            }
            if (!rewriteFile(file, updates)) {
                return false;
            }
            VALUES.putAll(updates);
            return true;
        } catch (Throwable throwable) {
            lastError = String.valueOf(throwable);
            return false;
        }
    }

    /** 按行重写：命中 key 的行替换值，没命中的追加到末尾；注释原样保留。 */
    private static boolean rewriteFile(Path file, Map<String, String> updates) throws java.io.IOException {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (Files.isRegularFile(file)) {
            lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        }
        java.util.Set<String> remaining = new java.util.LinkedHashSet<>(updates.keySet());
        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String line = rawLine.strip().replace('：', ':');
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            if (updates.containsKey(key)) {
                lines.set(i, key + " : " + updates.get(key));
                remaining.remove(key);
            }
        }
        for (String key : remaining) {
            lines.add(key + " : " + updates.get(key));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        return true;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String lastError() {
        return lastError;
    }

    public static int size() {
        return VALUES.size();
    }

    public static String raw(String key) {
        return VALUES.get(key.toLowerCase(Locale.ROOT));
    }

    public static boolean has(String key) {
        return VALUES.containsKey(key.toLowerCase(Locale.ROOT));
    }

    public static int getInt(String key, int defaultValue) {
        String value = raw(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            try {
                return (int) Double.parseDouble(value);
            } catch (Throwable ignored2) {
                return defaultValue;
            }
        }
    }

    public static double getDouble(String key, double defaultValue) {
        String value = raw(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = raw(key);
        if (value == null) {
            return defaultValue;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "1".equals(lower) || "yes".equals(lower) || "on".equals(lower)) {
            return true;
        }
        if ("false".equals(lower) || "0".equals(lower) || "no".equals(lower) || "off".equals(lower)) {
            return false;
        }
        return defaultValue;
    }

    public static String getString(String key, String defaultValue) {
        return VALUES.getOrDefault(key.toLowerCase(Locale.ROOT), defaultValue);
    }

    public static Map<String, String> snapshot() {
        return new LinkedHashMap<>(VALUES);
    }

    private static String defaultTemplate() {
        return """
//------------------------------------------------------------
// tacz-npc-modif  NPC-TACZ 全局修改文件
// 格式： 代码 : 数值 / true / false
// 修改后进游戏执行 /tacznpc reloadconfig 生效（标注“需重启”的除外）
//------------------------------------------------------------

// 代码：config_version
// 中文：配置格式版本（不要改）
config_version : 2

// 代码：target_creative_players
// 中文：是否把创造模式玩家当成攻击目标
// 下面 false = 不攻击（原版行为），true = 也攻击
target_creative_players : false

// 代码：target_spectator_players
// 中文：是否把旁观模式玩家当成攻击目标
target_spectator_players : false

// 代码：weak_run_distance
// 中文：玩家超过这个距离后 NPC 进入“弱运行”省性能状态
// 下面为格数，最低 16，默认 128；可用 /tacznpc weakrun <n> 修改
weak_run_distance : 128

// 代码：ally_alert_radius
// 中文：同阵营增援半径（格，默认 24）
ally_alert_radius : 24

// 代码：ally_alert_max
// 中文：一次最多叫多少同阵营增援（默认 4）
ally_alert_max : 4

// 代码：ally_alert_cooldown
// 中文：同一 NPC 两次增援的最小间隔（tick，默认 100）
ally_alert_cooldown : 100

// 代码：npc_speed_multiplier
// 中文：NPC 基础移速倍率（以僵尸 0.23 为基准，默认 1.3）
npc_speed_multiplier : 1.3

// 代码：pickup_delay_min
// 中文：掉落物至少在地上停留多少 tick 才被 NPC 拾取（20 tick=1 秒，默认 40）
pickup_delay_min : 40

// 代码：pickup_delay_max
// 中文：掉落物拾取延迟上限 tick（默认 60，即 2~3 秒）
pickup_delay_max : 60

// 代码：kill_feed
// 中文：击杀 NPC 时是否给击杀者显示击杀提示
kill_feed : true

// 代码：npc_bullet_tracer
// 中文：NPC 发射的 TACZ 子弹是否强制显示曳光弹道（true=显示，false=TACZ 原版）
npc_bullet_tracer : true

// 代码：ai_watchdog
// 中文：AI 看门狗：玩家附近 NPC 的 tick 被整合包跳过时记录日志并强制补 tick
ai_watchdog : false

// 代码：fallback_ai
// 中文：其它 AI 模组抑制 NPC 的 goal 时，是否用内置最小 AI 兜底（移动/索敌/开火）
fallback_ai : false

// 代码：trace_enabled
// 中文：调试日志（每 5 秒打印附近 NPC 状态）
trace_enabled : false

// 代码：drop_loot
// 中文：NPC 死亡是否掉落枪/弹药/金苹果
drop_loot : true

// 代码：corpse_lifetime_ticks
// 中文：NPC 尸体最多保留多少 tick 后强制移除（20 tick = 1 秒）
corpse_lifetime_ticks : 20

// 代码：gun_damage_fallback
// 中文：TACZ 枪械伤害被其它模组吞掉时，是否按 TACZ 给的伤害补一次（修复“枪打不死 NPC”）
// 下面 true = 开启兜底，false = 完全交给 TACZ
gun_damage_fallback : true

// 代码：combat_damage_multiplier
// 中文：NPC 枪械对玩家伤害倍率（1.0 = 原版）
combat_damage_multiplier : 1.0

// 代码：damage_cap_difficulty1 / 2 / 3 / 4
// 中文：各难度下 NPC 单发对玩家伤害上限（护甲减伤前）
damage_cap_difficulty1 : 6
damage_cap_difficulty2 : 9
damage_cap_difficulty3 : 12
damage_cap_difficulty4 : 15

// 代码：camera_kick_strength
// 中文：NPC 子弹命中玩家时的视角抖动倍率（0=关闭，1=默认，2=双倍）
camera_kick_strength : 1.0

// 代码：continuous_fire_players
// 中文：对玩家是否连续射击直到弹匣清空（false=恢复点射停顿）
continuous_fire_players : true

// 代码：npc_extra_spread_degrees
// 中文：NPC 基础额外散布（度，默认 1.5；越大越不准）
npc_extra_spread_degrees : 1.5

// 代码：accuracy_multiplier
// 中文：散布倍率（越小越准）
accuracy_multiplier : 0.75

// 代码：spread_degrees
// 中文：基础散布角度（度）
spread_degrees : 1.5

// 代码：npc_turn_delay_min
// 中文：NPC 切换目标后的最短转头时间（tick，默认 8）
npc_turn_delay_min : 8

// 代码：npc_turn_delay_max
// 中文：NPC 切换目标后的最长转头时间（tick，默认 16）
npc_turn_delay_max : 16

// 代码：npc_random_names
// 中文：是否给 NPC 生成随机姓名（默认 false，保持原本的 NPC 名）
npc_random_names : false

// 代码：difficulty3_spread_multiplier
// 中文：难度3的散布倍率（越小越准，默认 0.8）
difficulty3_spread_multiplier : 0.8

// 代码：difficulty4_spread_multiplier
// 中文：难度4的散布倍率（越小越准，默认 0.65）
difficulty4_spread_multiplier : 0.65

// 代码：npc_name_tags
// 中文：NPC 是否显示随机姓名/阵营名条
npc_name_tags : true

// 代码：attack_hostile_mobs
// 中文：NPC 是否主动攻击僵尸/骷髅等敌对生物（默认 false）
attack_hostile_mobs : false

// 代码：npc_voice
// 中文：NPC 受击/死亡是否播放人声
npc_voice : true

// 代码：npc_idle_look
// 中文：空闲时是否瞥一眼正面附近的玩家
npc_idle_look : true

// 代码：npc_callout_radius
// 中文：NPC 喊话只对附近多少格内玩家生效（默认 32）
npc_callout_radius : 32

// 代码：npc_callouts
// 中文：NPC 发现玩家时是否播放附近可闻的喊话（actionbar）
npc_callouts : true

// 代码：npc_aim_sway
// 中文：射击时是否有呼吸/持枪微摆
npc_aim_sway : true

// 代码：stand_still_when_shooting
// 中文：NPC 射击时是否优先站定（默认 true；只有敌人太近才移动）
stand_still_when_shooting : true

// 代码：reaction_time_multiplier
// 中文：反应时间倍率（0.5 = 快一倍，2.0 = 慢一倍；当前默认 1.5，反应更久）
reaction_time_multiplier : 1.5

// 代码：refill_seconds_ranged
// 中文：远程状态每隔多少秒补一次备弹
refill_seconds_ranged : 15

// 代码：refill_seconds_melee
// 中文：近战状态每隔多少秒补一次备弹
refill_seconds_melee : 5

// 代码：vision_through_glass
// 中文：NPC 能否看穿玻璃类方块
vision_through_glass : false

// 代码：vision_through_leaves
// 中文：NPC 能否看穿树叶
vision_through_leaves : true

// 代码：vision_max_distance
// 中文：NPC 视线最大距离（格）
vision_max_distance : 192

// 代码：friendly_fire_safety
// 中文：友军挡在枪线上时是否不开火
friendly_fire_safety : true

// 代码：same_faction_immune
// 中文：同阵营 NPC 之间是否完全免伤
same_faction_immune : true

// 代码：npc_max_default
// 中文：新世界默认 NPC 总数上限（已有世界用 /tacznpc npcmax）
npc_max_default : 32
""";
    }
}
