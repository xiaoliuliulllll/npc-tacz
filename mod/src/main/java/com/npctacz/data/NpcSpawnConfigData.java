package com.npctacz.data;

import com.npctacz.entity.NpcFaction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.EnumMap;

/**
 * NPC 自然生成配置（世界级持久数据）。
 *
 * <p>每个阵营一条规则：</p>
 * <ul>
 *   <li>{@code intervalTicks} 刷新间隔；</li>
 *   <li>{@code amountMin/amountMax} 每次生成数量范围；</li>
 *   <li>{@code innerRadius/outerRadius} 玩家外圈/内圈距离（默认 48~96 格）。</li>
 * </ul>
 *
 * <p>默认值：</p>
 * <pre>
 * 士兵/劫匪/叛军：每 10 分钟，3~5 / 4~6 / 2~3，48~96 格
 * 雇佣兵：每 3 天 1 个
 * 赏金猎人：每 5 天 1 个（2 天后或达成击杀次数自然消失）
 * </pre>
 */
public final class NpcSpawnConfigData extends SavedData {

    private static final String DATA_NAME = "npc_tacz_spawn_config";

    public static final long TICKS_PER_SECOND = 20L;
    public static final long TICKS_PER_MINUTE = 60L * TICKS_PER_SECOND;
    public static final long TICKS_PER_DAY = 24000L;

    public static final class Rule {
        public long intervalTicks;
        public int amountMin;
        public int amountMax;
        public double innerRadius;
        public double outerRadius;
        /** 该阵营是否参与自然生成。 */
        public boolean enabled = true;

        public Rule(long intervalTicks, int amountMin, int amountMax,
                    double innerRadius, double outerRadius) {
            this.intervalTicks = intervalTicks;
            this.amountMin = amountMin;
            this.amountMax = amountMax;
            this.innerRadius = innerRadius;
            this.outerRadius = outerRadius;
        }

        public Rule copy() {
            Rule rule = new Rule(intervalTicks, amountMin, amountMax, innerRadius, outerRadius);
            rule.enabled = enabled;
            return rule;
        }
    }

    private final EnumMap<NpcFaction, Rule> rules = new EnumMap<>(NpcFaction.class);
    /** 最近一次“全部间隔”设置的 tick 数；用于界面显示，不再依赖第一个阵营。 */
    private long allIntervalTicks = 10L * TICKS_PER_MINUTE;

    public static NpcSpawnConfigData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
            NpcSpawnConfigData::load,
            NpcSpawnConfigData::new,
            DATA_NAME
        );
    }

    /** 默认规则。 */
    public static Rule defaultRule(NpcFaction faction) {
        return switch (faction) {
            case SOLDIER -> new Rule(10L * TICKS_PER_MINUTE, 3, 5, 48.0D, 96.0D);
            case BANDIT -> new Rule(10L * TICKS_PER_MINUTE, 4, 6, 48.0D, 96.0D);
            case REBEL -> new Rule(10L * TICKS_PER_MINUTE, 2, 3, 48.0D, 96.0D);
            case MERCENARY -> new Rule(3L * TICKS_PER_DAY, 1, 1, 48.0D, 96.0D);
            case BOUNTY_HUNTER -> new Rule(5L * TICKS_PER_DAY, 1, 1, 48.0D, 96.0D);
        };
    }

    public Rule rule(NpcFaction faction) {
        return this.rules.computeIfAbsent(faction, NpcSpawnConfigData::defaultRule);
    }

    public boolean isEnabled(NpcFaction faction) {
        return this.rule(faction).enabled;
    }

    public void setEnabled(NpcFaction faction, boolean enabled) {
        this.rule(faction).enabled = enabled;
        this.setDirty();
    }

    public void setInterval(NpcFaction faction, long ticks) {
        rule(faction).intervalTicks = Math.max(TICKS_PER_SECOND, ticks);
        this.setDirty();
    }

    public long getAllIntervalTicks() {
        return this.allIntervalTicks;
    }

    /** 设置“全部间隔”：同时更新显示值和所有阵营。 */
    public void setAllInterval(long ticks) {
        this.allIntervalTicks = Math.max(TICKS_PER_SECOND, ticks);
        for (NpcFaction faction : NpcFaction.values()) {
            this.rule(faction).intervalTicks = this.allIntervalTicks;
        }
        this.setDirty();
    }

    public void setAmount(NpcFaction faction, int min, int max) {
        Rule rule = rule(faction);
        rule.amountMin = Math.max(0, Math.min(min, max));
        rule.amountMax = Math.max(rule.amountMin, max);
        this.setDirty();
    }

    public void setRange(NpcFaction faction, double outer, double inner) {
        Rule rule = rule(faction);
        rule.outerRadius = Math.max(16.0D, Math.min(4096.0D, outer));
        rule.innerRadius = Math.max(16.0D, Math.min(rule.outerRadius, inner));
        this.setDirty();
    }

    public void resetDefaults() {
        this.rules.clear();
        this.allIntervalTicks = 10L * TICKS_PER_MINUTE;
        for (NpcFaction faction : NpcFaction.values()) {
            this.rules.put(faction, defaultRule(faction));
        }
        this.setDirty();
    }

    private static NpcSpawnConfigData load(CompoundTag tag) {
        NpcSpawnConfigData data = new NpcSpawnConfigData();
        CompoundTag rules = tag.getCompound("Rules");
        for (NpcFaction faction : NpcFaction.values()) {
            Rule rule = defaultRule(faction);
            if (rules.contains(faction.name())) {
                CompoundTag entry = rules.getCompound(faction.name());
                if (entry.contains("Interval")) {
                    rule.intervalTicks = entry.getLong("Interval");
                }
                if (entry.contains("AmountMin")) {
                    rule.amountMin = entry.getInt("AmountMin");
                }
                if (entry.contains("AmountMax")) {
                    rule.amountMax = entry.getInt("AmountMax");
                }
                if (entry.contains("Inner")) {
                    rule.innerRadius = entry.getDouble("Inner");
                }
                if (entry.contains("Outer")) {
                    rule.outerRadius = entry.getDouble("Outer");
                }
                if (entry.contains("Enabled")) {
                    rule.enabled = entry.getBoolean("Enabled");
                }
            }
            data.rules.put(faction, rule);
        }
        data.allIntervalTicks = tag.contains("AllInterval")
            ? Math.max(TICKS_PER_SECOND, tag.getLong("AllInterval"))
            : data.rule(NpcFaction.SOLDIER).intervalTicks;
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag rules = new CompoundTag();
        for (NpcFaction faction : NpcFaction.values()) {
            Rule rule = rule(faction);
            CompoundTag entry = new CompoundTag();
            entry.putLong("Interval", rule.intervalTicks);
            entry.putInt("AmountMin", rule.amountMin);
            entry.putInt("AmountMax", rule.amountMax);
            entry.putDouble("Inner", rule.innerRadius);
            entry.putDouble("Outer", rule.outerRadius);
            entry.putBoolean("Enabled", rule.enabled);
            rules.put(faction.name(), entry);
        }
        tag.put("Rules", rules);
        tag.putLong("AllInterval", this.allIntervalTicks);
        return tag;
    }

    /** 把 tick 数格式化成可读的中文时间。 */
    public static String formatInterval(long ticks) {
        if (ticks % TICKS_PER_DAY == 0L && ticks >= TICKS_PER_DAY) {
            return (ticks / TICKS_PER_DAY) + "天";
        }
        if (ticks % TICKS_PER_MINUTE == 0L && ticks >= TICKS_PER_MINUTE) {
            return (ticks / TICKS_PER_MINUTE) + "分钟";
        }
        if (ticks % TICKS_PER_SECOND == 0L) {
            return (ticks / TICKS_PER_SECOND) + "秒";
        }
        return ticks + "tick";
    }
}
