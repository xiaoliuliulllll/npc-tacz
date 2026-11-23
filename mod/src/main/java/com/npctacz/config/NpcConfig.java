package com.npctacz.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * NPC 战斗/寻路参数（自动生成 config/npc-tacz-common.toml，无需手动添加）。
 *
 * <p>所有数值都有默认值；改配置后重进世界生效。代码里只通过 {@link #VALUES}
 * 读取，避免散落的魔法数字。</p>
 */
public final class NpcConfig {

    /** 配置值包装。 */
    public static final class Values {
        public final ForgeConfigSpec.IntValue reactionTicks;
        public final ForgeConfigSpec.IntValue burstShots;
        public final ForgeConfigSpec.IntValue burstPauseTicks;
    /** 对玩家是否连续射击直到弹匣打空（true = 不停顿点射）。 */
    public final ForgeConfigSpec.BooleanValue continuousFireOnPlayers;
    /** NPC 子弹命中玩家时视角抖动倍率（1.0 = 加强后的默认值）。 */
    public final ForgeConfigSpec.DoubleValue hitKickStrength;
        public final ForgeConfigSpec.DoubleValue accuracyMultiplier;
        public final ForgeConfigSpec.DoubleValue spreadDegrees;
        public final ForgeConfigSpec.BooleanValue friendlyFireSafety;
        public final ForgeConfigSpec.BooleanValue useCover;
        public final ForgeConfigSpec.IntValue coverSearchRadius;
        public final ForgeConfigSpec.DoubleValue coverHealthThreshold;
        public final ForgeConfigSpec.DoubleValue aggressionMultiplier;
        public final ForgeConfigSpec.IntValue refillSecondsRanged;
        public final ForgeConfigSpec.IntValue refillSecondsMelee;
        public final ForgeConfigSpec.BooleanValue pathStuckJump;
        public final ForgeConfigSpec.IntValue stuckDetourTicks;
        public final ForgeConfigSpec.BooleanValue breakDoors;
        public final ForgeConfigSpec.IntValue ironDoorBreakTicksMin;
        public final ForgeConfigSpec.IntValue ironDoorBreakTicksMax;
        public final ForgeConfigSpec.ConfigValue<String> meleeWeapon;
        public final ForgeConfigSpec.IntValue targetScanIntervalTicks;
        public final ForgeConfigSpec.DoubleValue targetSwitchCost;
        public final ForgeConfigSpec.BooleanValue traceEnabled;

        private Values(ForgeConfigSpec.Builder builder) {
            builder.comment("NPC 战斗设置 / NPC combat tuning").push("combat");
            this.reactionTicks = builder
                .comment("看到目标后需要连续看到多少 tick 才开火 / ticks of LOS before opening fire")
                .defineInRange("reactionTicks", 6, 0, 40);
            this.burstShots = builder
                .comment("每个连射点射几发 / shots per burst")
                .defineInRange("burstShots", 4, 1, 30);
            this.burstPauseTicks = builder
                .comment("两轮点射之间停顿多久 / pause between bursts")
                .defineInRange("burstPauseTicks", 30, 0, 100);
            this.continuousFireOnPlayers = builder
                .comment("对玩家连续射击直到弹匣清空（不吃点射停顿）/ full-auto at players until magazine empty")
                .define("continuousFireOnPlayers", false);
            this.hitKickStrength = builder
                .comment("NPC 子弹命中玩家时视角抖动倍率（1=加强默认，0=关闭）/ camera kick multiplier when hit by NPC bullets")
                .defineInRange("hitKickStrength", 1.0D, 0.0D, 5.0D);
            this.accuracyMultiplier = builder
                .comment("散布倍率：越小越准 / spread multiplier, lower = more accurate")
                .defineInRange("accuracyMultiplier", 0.75D, 0.1D, 5.0D);
            this.spreadDegrees = builder
                .comment("基础散布角度（度）/ base aim spread in degrees")
                .defineInRange("spreadDegrees", 1.5D, 0.0D, 15.0D);
            this.friendlyFireSafety = builder
                .comment("友军在射线上时不开火 / hold fire when an ally is in the line of fire")
                .define("friendlyFireSafety", true);
            this.useCover = builder
                .comment("血量低时寻找掩体 / seek cover when health is low")
                .define("useCover", true);
            this.coverSearchRadius = builder
                .comment("掩体搜索半径（格）/ cover search radius")
                .defineInRange("coverSearchRadius", 8, 2, 24);
            this.coverHealthThreshold = builder
                .comment("血量低于此比例时找掩体 / health ratio to seek cover")
                .defineInRange("coverHealthThreshold", 0.4D, 0.05D, 1.0D);
            this.aggressionMultiplier = builder
                .comment("交战距离倍率：越大越激进 / engagement range multiplier")
                .defineInRange("aggressionMultiplier", 1.0D, 0.5D, 2.0D);
            builder.pop();

            builder.comment("弹药补充 / ammo refill").push("ammo");
            this.refillSecondsRanged = builder
                .comment("远程状态下每隔多少秒补一次备弹 / seconds per refill while ranged")
                .defineInRange("refillSecondsRanged", 15, 2, 120);
            this.refillSecondsMelee = builder
                .comment("近战状态下每隔多少秒补一次备弹 / seconds per refill while melee")
                .defineInRange("refillSecondsMelee", 5, 1, 60);
            this.meleeWeapon = builder
                .comment("弹尽后切换的近战武器物品 id / melee weapon item id")
                .define("meleeWeapon", "minecraft:iron_sword");
            builder.pop();

            builder.comment("寻路 / pathfinding").push("pathfinding");
            this.pathStuckJump = builder
                .comment("卡住时自动跳跃 / auto jump when stuck")
                .define("pathStuckJump", true);
            this.stuckDetourTicks = builder
                .comment("卡住多久后换一个绕行点 / ticks stuck before taking a detour")
                .defineInRange("stuckDetourTicks", 40, 10, 200);
            this.breakDoors = builder
                .comment("是否允许开门/破门 / allow opening and breaking doors")
                .define("breakDoors", true);
            this.ironDoorBreakTicksMin = builder
                .comment("铁门破坏最短时间（tick）/ iron door break time min")
                .defineInRange("ironDoorBreakTicksMin", 20, 5, 200);
            this.ironDoorBreakTicksMax = builder
                .comment("铁门破坏最长时间（tick）/ iron door break time max")
                .defineInRange("ironDoorBreakTicksMax", 40, 5, 200);
            builder.pop();

            builder.comment("索敌 / targeting").push("targeting");
            this.targetScanIntervalTicks = builder
                .comment("重新索敌间隔（tick）/ target scan interval")
                .defineInRange("targetScanIntervalTicks", 20, 5, 100);
            this.targetSwitchCost = builder
                .comment("换目标需要的分数优势（越大越不轻易换目标）/ bonus kept by current target")
                .defineInRange("targetSwitchCost", 0.35D, 0.0D, 2.0D);
            builder.pop();

            builder.comment("调试 / debug").push("debug");
            this.traceEnabled = builder
                .comment("每 5 秒在日志打印一次 NPC 状态（排查用）/ trace NPC state")
                .define("traceEnabled", false);
            builder.pop();
        }
    }

    public static final ForgeConfigSpec SPEC;
    public static final Values VALUES;

    static {
        Pair<Values, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Values::new);
        VALUES = pair.getLeft();
        SPEC = pair.getRight();
    }

    private NpcConfig() {
    }
}
