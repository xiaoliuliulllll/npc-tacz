package com.npctacz.entity.ai;

import com.npctacz.data.NpcDifficultyData;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * NPC 反应时间系统。
 *
 * <p>公式：{@code f(x) = t + 0.25 * n}</p>
 * <ul>
 *   <li>{@code t}：该难度的最小反应时间（秒）；</li>
 *   <li>{@code n}：距离系数，{@code n = 1 + floor(距离方块 / 16)}，即初始 1 个区块，
 *       每多 16 格 +1；</li>
 *   <li>结果按该难度的 [最小, 最大] 区间夹紧；</li>
 *   <li>难度 4 额外：玩家连续击杀 NPC，每 10 个让全体 NPC 反应时间 -20%（0.8 的幂），
 *       最低 0.065s；玩家死亡后清零恢复。</li>
 * </ul>
 *
 * <p>难度区间：</p>
 * <pre>
 * 1: 1.2 ~ 2.6s
 * 2: 0.8 ~ 1.6s
 * 3: 0.4 ~ 0.8s
 * 4: 0.2 ~ 0.6s
 * </pre>
 */
public final class NpcReactionTime {

    /** 每个难度：{最小秒, 最大秒}。 */
    private static final float[][] MIN_MAX = {
        {1.2F, 2.6F},
        {0.8F, 1.6F},
        {0.4F, 0.8F},
        {0.2F, 0.6F}
    };

    /** 每 16 格增加的反应时间（秒）。 */
    private static final float DISTANCE_STEP_SECONDS = 0.25F;
    private static final double CHUNK_BLOCKS = 16.0D;
    /** 难度 4 连杀加成后的绝对下限。 */
    private static final float MIN_POSSIBLE_SECONDS = 0.065F;
    private static final String KILL_STREAK_TAG = "NpcTaczD4KillStreak";
    /** 难度缓存：每 20 tick 或换世界时刷新，避免每个 NPC 每 tick 查 SavedData。 */
    private static ServerLevel cachedLevel;
    private static int cachedDifficulty = 2;
    private static float cachedReactionMultiplier = 1.0F;
    private static long cachedDifficultyTick = Long.MIN_VALUE;
    private static final double STREAK_FACTOR_PER_10 = 0.8D;

    private NpcReactionTime() {
    }

    /** 计算某个 NPC 对目标所需的反应 tick 数（至少 1 tick）。 */
    public static int requiredTicks(WastelandNpcEntity npc, LivingEntity target, double acquireDistance) {
        if (npc == null || target == null) {
            return 20;
        }
        int difficulty;
        if (npc.level() instanceof ServerLevel serverLevel) {
            difficulty = difficultyOf(serverLevel);
        } else {
            difficulty = npc.getNpcDifficulty();
        }
        float multiplier = multiplierFor(target, difficulty);
        float seconds = seconds(difficulty, acquireDistance, multiplier);
        if (npc.level() instanceof ServerLevel) {
            // 管理员界面倍率；由 difficultyOf 每 20 tick 缓存，避免每次索敌都查 SavedData
            seconds *= cachedReactionMultiplier;
        }
        return Math.max(1, Mth.ceil(seconds * 20.0F));
    }

    /** 每 20 tick 刷新一次难度缓存（SavedData 查询不便宜，NPC 多时很关键）。 */
    private static int difficultyOf(ServerLevel level) {
        long now = level.getGameTime();
        if (level != cachedLevel || cachedDifficultyTick == Long.MIN_VALUE
            || now < cachedDifficultyTick || now - cachedDifficultyTick > 20L) {
            cachedLevel = level;
            NpcDifficultyData data = NpcDifficultyData.get(level);
            cachedDifficulty = data.getDifficulty();
            cachedReactionMultiplier = (float) data.getReactionTimeMultiplier();
            cachedDifficultyTick = now;
        }
        return cachedDifficulty;
    }

    /** 核心公式 f(x) = t + 0.25n，夹紧后再乘连杀系数。 */
    public static float seconds(int difficulty, double distanceBlocks, float multiplier) {
        int index = Mth.clamp(difficulty, 1, 4) - 1;
        float min = MIN_MAX[index][0];
        float max = MIN_MAX[index][1];
        int chunks = 1 + (int) Math.floor(Math.max(0.0D, distanceBlocks) / CHUNK_BLOCKS);
        float seconds = min + DISTANCE_STEP_SECONDS * chunks;
        seconds = Mth.clamp(seconds, min, max);
        seconds *= Math.max(0.0F, multiplier);
        seconds *= (float) com.npctacz.config.NpcGlobalConfig.getDouble("reaction_time_multiplier", 1.5D);
        return Math.max(MIN_POSSIBLE_SECONDS, seconds);
    }

    /** 难度 4 且目标玩家有连杀时返回反应时间系数，否则 1.0。 */
    public static float multiplierFor(LivingEntity target, int difficulty) {
        if (difficulty != 4 || !(target instanceof ServerPlayer player)) {
            return 1.0F;
        }
        return streakMultiplier(player);
    }

    public static int killStreak(ServerPlayer player) {
        return player.getPersistentData().getInt(KILL_STREAK_TAG);
    }

    /** 每 10 杀减少 20%，即 0.8^floor(kills/10)，下限由调用方夹到 0.065s。 */
    public static float streakMultiplier(ServerPlayer player) {
        int steps = killStreak(player) / 10;
        if (steps <= 0) {
            return 1.0F;
        }
        return (float) Math.pow(STREAK_FACTOR_PER_10, steps);
    }

    /** NPC 被玩家击杀：难度 4 时累积该玩家的连杀数。 */
    public static void onNpcKilled(WastelandNpcEntity npc, LivingEntity killer) {
        try {
            if (!(npc.level() instanceof ServerLevel level)) {
                return;
            }
            if (NpcDifficultyData.get(level).getDifficulty() != 4) {
                return;
            }
            if (!(killer instanceof ServerPlayer player)) {
                return;
            }
            int kills = player.getPersistentData().getInt(KILL_STREAK_TAG) + 1;
            player.getPersistentData().putInt(KILL_STREAK_TAG, kills);
            if (kills % 10 == 0
                && NpcDifficultyData.get(level).areChatNotificationsEnabled(player.getUUID())) {
                float multiplier = (float) Math.pow(STREAK_FACTOR_PER_10, kills / 10);
                int percent = Math.round((1.0F - multiplier) * 100.0F);
                player.displayClientMessage(Component.literal(
                    "§c§l[难度4] §6连杀 " + kills + " 个 NPC：全体 NPC 反应时间 §e-" + percent + "%"
                ), false);
            }
        } catch (Throwable ignored) {
            // 击杀统计失败不能影响战斗
        }
    }

    /** 玩家死亡：难度 4 的连杀加成清零恢复。 */
    public static void resetOnPlayerDeath(ServerPlayer player) {
        try {
            boolean had = player.getPersistentData().getInt(KILL_STREAK_TAG) > 0;
            player.getPersistentData().remove(KILL_STREAK_TAG);
            if (had && player.level() instanceof ServerLevel level
                && NpcDifficultyData.get(level).getDifficulty() == 4
                && NpcDifficultyData.get(level).areChatNotificationsEnabled(player.getUUID())) {
                player.displayClientMessage(Component.literal(
                    "§c§l[难度4] §7你死亡了，NPC 反应时间加成已重置"
                ), false);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
