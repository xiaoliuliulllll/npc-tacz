package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.data.NpcDifficultyData;
import com.npctacz.data.NpcSpawnConfigData;
import com.npctacz.entity.NpcFaction;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 新的自然生成调度器。
 *
 * <p>按 {@link NpcSpawnConfigData} 的配置，为每个阵营维护一个倒计时：</p>
 * <ul>
 *   <li>到时间 → 在随机玩家周围(内圈~外圈)生成一批 NPC；</li>
 *   <li><b>错峰生成</b>：批内每个 NPC 之间间隔 50~110 tick，不会同一 tick 一起刷出来；</li>
 *   <li>数量、范围、间隔都可以用 {@code /tacznpc spawn_config} 修改并持久化。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcSpawnEvents {

    /** NPC 总数上限由 NpcDifficultyData.maxNpcs 控制（默认 32，/tacznpc npcmax 修改）。 */
    /** 批内错峰间隔基数。 */
    private static final int STAGGER_BASE_TICKS = 50;
    private static final int STAGGER_RANDOM_TICKS = 60;

    /** 待生成的队列项。 */
    private record PendingSpawn(long dueTick, UUID playerId, ServerLevel level, NpcFaction faction) {
    }

    private static final List<PendingSpawn> PENDING = new ArrayList<>();
    private static final EnumMap<NpcFaction, Long> NEXT_SPAWN = new EnumMap<>(NpcFaction.class);
    private static ServerLevel schedulerLevel;
    private static long lastPruneTick = Long.MIN_VALUE;

    private NpcSpawnEvents() {
    }

    /** 玩家执行 clear 后调用：清空待生成队列并失效计数缓存。 */
    public static void clearPending() {
        PENDING.clear();
        invalidateCountCache();
    }

    /** 配置变更后调用：下一次生成时间按新间隔重新起算。 */
    public static void onConfigChanged() {
        NEXT_SPAWN.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        if (schedulerLevel != overworld) {
            schedulerLevel = overworld;
            NEXT_SPAWN.clear();
            PENDING.clear();
        }
        if (overworld.getDifficulty() == Difficulty.PEACEFUL) {
            PENDING.clear();
            return;
        }

        long now = overworld.getGameTime();
        NpcSpawnConfigData config = NpcSpawnConfigData.get(overworld);

        // 1) 每个阵营的倒计时
        for (NpcFaction faction : NpcFaction.values()) {
            long next = NEXT_SPAWN.computeIfAbsent(faction,
                key -> now + config.rule(key).intervalTicks);
            if (now < next) {
                continue;
            }
            NEXT_SPAWN.put(faction, now + config.rule(faction).intervalTicks);
            if (config.rule(faction).enabled) {
                scheduleBatch(server, faction, config);
            }
        }

        // 2) 处理错峰队列
        processPending(server, now, config);

        // 3) 上限附近的空闲/远离玩家的 NPC 定期清理，避免卡住自然生成
        pruneIdleNpcs(server, now);
    }

    /** 为某个阵营排一批生成（数量按配置随机），彼此错开时间。 */
    private static void scheduleBatch(MinecraftServer server, NpcFaction faction,
                                      NpcSpawnConfigData config) {
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isSpectator()) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        NpcSpawnConfigData.Rule rule = config.rule(faction);
        if (!rule.enabled) {
            return;
        }
        int min = Math.max(0, rule.amountMin);
        int max = Math.max(min, rule.amountMax);
        int amount = min + (max > min ? server.overworld().getRandom().nextInt(max - min + 1) : 0);
        if (amount <= 0) {
            return;
        }
        // 按独立上限裁剪：满了先尝试清理一个远处空闲 NPC，仍满就整批不排、也不发假公告
        int cap = Math.max(1, NpcDifficultyData.get(server.overworld()).getMaxNpcs());
        int capacity = Math.max(0, cap - countAllNpcs(server));
        if (capacity <= 0) {
            freeIdleNpcSlot(server);
            capacity = Math.max(0, cap - countAllNpcs(server));
        }
        if (capacity <= 0) {
            return;
        }
        amount = Math.min(amount, capacity);
        // 同一批只挑一个玩家，避免瞬时全图刷新
        ServerPlayer target = players.get(server.overworld().getRandom().nextInt(players.size()));
        ServerLevel level = (ServerLevel) target.level();
        long now = server.overworld().getGameTime();
        long delay = 0L;
        for (int i = 0; i < amount; i++) {
            if (i > 0) {
                delay += STAGGER_BASE_TICKS
                    + level.getRandom().nextInt(STAGGER_RANDOM_TICKS + 1);
            }
            PENDING.add(new PendingSpawn(now + delay, target.getUUID(), level, faction));
        }
        if (NpcDifficultyData.get(level).isGeneratePrompt()
            && NpcDifficultyData.get(level).areChatNotificationsEnabled(target.getUUID())) {
            target.displayClientMessage(Component.literal(
                "§e⚠ 附近出现了 §f" + chineseName(faction) + " §7x" + amount + "（分批靠近）"
            ), false);
        }
    }

    private static void processPending(MinecraftServer server, long now, NpcSpawnConfigData config) {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<PendingSpawn> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingSpawn pending = iterator.next();
            if (now < pending.dueTick()) {
                continue;
            }
            iterator.remove();
            if (!config.rule(pending.faction()).enabled) {
                continue;
            }
            ServerLevel level = pending.level();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(pending.playerId());
            if (player == null || !player.level().equals(level)) {
                continue;
            }
            if (level.getDifficulty() == Difficulty.PEACEFUL) {
                continue;
            }
            int existing = countAllNpcs(level.getServer());
            int maxNpcs = Math.max(1, NpcDifficultyData.get(level).getMaxNpcs());
            if (existing >= maxNpcs) {
                freeIdleNpcSlot(level.getServer());
                existing = countAllNpcs(level.getServer());
                if (existing >= maxNpcs) {
                    continue;
                }
            }
            EntityType<?> type = entityTypeFor(pending.faction());
            if (type == null) {
                continue;
            }
            if (trySpawnOne(level, player, type, config.rule(pending.faction()))) {
                invalidateCountCache();
            }
        }
    }

    /** 生成单个 NPC：外圈/内圈随机，位置校验失败最多重试 8 次。 */
    private static boolean trySpawnOne(ServerLevel level, ServerPlayer player,
                                       EntityType<?> type, NpcSpawnConfigData.Rule rule) {
        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double inner = Math.max(16.0D, rule.innerRadius);
            double outer = Math.max(inner + 1.0D, rule.outerRadius);
            double distance = inner + random.nextDouble() * (outer - inner);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (spawnAt(level, player, x, z, type, false)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static EntityType<?> entityTypeFor(NpcFaction faction) {
        return switch (faction) {
            case SOLDIER -> ModRegistry.SOLDIER.get();
            case BANDIT -> ModRegistry.BANDIT.get();
            case REBEL -> ModRegistry.REBEL.get();
            case MERCENARY -> ModRegistry.MERCENARY.get();
            case BOUNTY_HUNTER -> ModRegistry.BOUNTY_HUNTER.get();
        };
    }

    private static String chineseName(NpcFaction faction) {
        return switch (faction) {
            case SOLDIER -> "士兵";
            case BANDIT -> "劫匪";
            case REBEL -> "叛军";
            case MERCENARY -> "雇佣兵";
            case BOUNTY_HUNTER -> "赏金猎人";
        };
    }

    /** 全服 NPC 总数缓存（20 tick），避免每次生成都全维度扫实体。 */
    private static MinecraftServer cachedServer;
    private static int cachedNpcCount;
    private static long cachedCountTick = Long.MIN_VALUE;

    /** 生成/清除/移除 NPC 后调用，立刻作废旧计数缓存。 */
    public static void invalidateCountCache() {
        cachedCountTick = Long.MIN_VALUE;
    }

    public static int countAllNpcs(MinecraftServer server) {
        long now = server.overworld() == null ? 0L : server.overworld().getGameTime();
        if (server == cachedServer && Math.abs(now - cachedCountTick) < 20L) {
            return cachedNpcCount;
        }
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof WastelandNpcEntity) {
                    count++;
                }
            }
        }
        cachedServer = server;
        cachedNpcCount = count;
        cachedCountTick = now;
        return count;
    }

    // ------------------------------------------------------------------ 上限清理 / 强制生成

    /** 清理一个“远离所有玩家、无目标、非雇佣、非强制停留”的空闲 NPC 来腾上限位置。 */
    private static boolean freeIdleNpcSlot(MinecraftServer server) {
        long now = server.overworld() == null ? 0L : server.overworld().getGameTime();
        WastelandNpcEntity candidate = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof WastelandNpcEntity npc) || !npc.isAlive()) {
                    continue;
                }
                if (npc instanceof com.npctacz.entity.MercenaryNpc mercenary && mercenary.isHired()) {
                    continue;
                }
                // 赏金猎人有自己的 2 天生命周期，不在这里提前清
                if (npc instanceof com.npctacz.entity.BountyHunterNpc) {
                    continue;
                }
                if (npc.getTarget() != null && npc.getTarget().isAlive()) {
                    continue;
                }
                // 刚生成/刚被攻击的不清，避免打一半怪消失
                if (npc.tickCount < 600 || npc.getLastHurtByMob() != null) {
                    continue;
                }
                // 160 格内还有玩家时不清，避免清掉玩家正在追踪的实体导致客户端残影
                if (npc.level().getNearestPlayer(npc, 160.0D) != null) {
                    continue;
                }
                double nearest = npc.level().getNearestPlayer(npc, 9999.0D) == null
                    ? 9999.0D : npc.level().getNearestPlayer(npc, 9999.0D).distanceToSqr(npc);
                if (nearest > bestScore) {
                    bestScore = nearest;
                    candidate = npc;
                }
            }
        }
        if (candidate == null) {
            return false;
        }
        candidate.discard();
        invalidateCountCache();
        if (countAllNpcs(server) <= 0) {
            return true;
        }
        return true;
    }

    /** 每 100 tick 检查：NPC 数量接近/达到上限时，清掉远离玩家且无目标的 NPC，避免上限永久卡死。 */
    private static void pruneIdleNpcs(MinecraftServer server, long now) {
        if (now - lastPruneTick < 100L) {
            return;
        }
        lastPruneTick = now;
        int cap = Math.max(1, NpcDifficultyData.get(server.overworld()).getMaxNpcs());
        int count = countAllNpcs(server);
        if (count < Math.max(1, cap - 2)) {
            return;
        }
        int removed = 0;
        while (count >= cap && removed < 4) {
            if (!freeIdleNpcSlot(server)) {
                break;
            }
            removed++;
            count = countAllNpcs(server);
        }
    }

    /** 立即按自然生成规则刷一波（/tacznpc spawnnow 用），返回真实生成数量。 */
    public static int forceSpawnWave(MinecraftServer server, @Nullable NpcFaction faction) {
        if (server == null) {
            return 0;
        }
        ServerLevel level = server.overworld();
        if (level == null) {
            return 0;
        }
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isSpectator()) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            return 0;
        }
        NpcSpawnConfigData config = NpcSpawnConfigData.get(level);
        int cap = Math.max(1, NpcDifficultyData.get(level).getMaxNpcs());
        int spawned = 0;
        for (NpcFaction current : faction == null ? NpcFaction.values() : new NpcFaction[]{faction}) {
            NpcSpawnConfigData.Rule rule = config.rule(current);
            int amount = Math.max(1, Math.min(8, rule.amountMin));
            for (int i = 0; i < amount; i++) {
                ServerPlayer target = players.get(level.getRandom().nextInt(players.size()));
                int capacity = cap - countAllNpcs(server);
                if (capacity <= 0) {
                    return spawned;
                }
                EntityType<?> type = entityTypeFor(current);
                if (type != null && trySpawnOne((ServerLevel) target.level(), target, type, rule)) {
                    spawned++;
                    invalidateCountCache();
                }
            }
        }
        return spawned;
    }

    /** 给 /tacznpc diagnose 用：每个阵营下一次自然生成还有多少秒。 */
    public static String nextSpawnSummary(MinecraftServer server) {
        if (server == null || server.overworld() == null) {
            return "";
        }
        long now = server.overworld().getGameTime();
        StringBuilder builder = new StringBuilder();
        NpcSpawnConfigData config = NpcSpawnConfigData.get(server.overworld());
        for (NpcFaction faction : NpcFaction.values()) {
            Long next = NEXT_SPAWN.get(faction);
            long remaining = next == null
                ? config.rule(faction).intervalTicks
                : Math.max(0, next - now);
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(chineseName(faction)).append(':').append(remaining / 20L).append('s');
        }
        return builder.toString();
    }

    private static boolean spawnAt(ServerLevel level, ServerPlayer player,
                                   int x, int z, EntityType<?> type, boolean forceLoad) {
        // Natural spawning never force-loads a chunk. Commands may explicitly
        // load the target chunk because the player asked for it.
        if (forceLoad) {
            level.getChunk(x >> 4, z >> 4);
        } else if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
            return false;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 与玩家高度差过大（浮空岛顶/悬崖/洞穴上方）不生成：否则 NPC 会摔死或卡在高处
        if (Math.abs(y - player.getY()) > 24.0D) {
            return false;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (!forceLoad && !level.isPositionEntityTicking(pos)) return false;
        if (!level.getBlockState(pos.below()).isSolid()) return false;
        if (!level.getBlockState(pos).getFluidState().isEmpty()) return false;
        if (!level.getBlockState(pos.above()).getFluidState().isEmpty()) return false;
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return false;
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) return false;

        Entity entity = type.spawn(level, pos, MobSpawnType.EVENT);
        return entity != null;
    }

    /** Used by /tacznpc spawn. 生成在玩家前方 4~13 格，落点会检查地面/水/碰撞和间距。 */
    public static int spawnForCommand(ServerLevel level, ServerPlayer player,
                                      EntityType<?> type, int count, boolean farGroup) {
        // 独立 NPC 上限：全服总数到上限后，指令也不再继续生成
        int cap = Math.max(1, NpcDifficultyData.get(level).getMaxNpcs());
        int existing = countAllNpcs(level.getServer());
        count = Math.min(count, Math.max(0, cap - existing));
        if (count <= 0) {
            return 0;
        }
        RandomSource random = level.getRandom();
        // MC 里 yaw=0 是 +Z 方向：前方向量 = (-sin(yaw), cos(yaw))
        double yaw = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double forwardAngle = Math.atan2(forwardZ, forwardX);
        List<net.minecraft.world.phys.Vec3> placed = new ArrayList<>();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            boolean success = false;
            for (int attempt = 0; attempt < 24 && !success; attempt++) {
                // 前三次优先玩家正前方（±20 度），之后在周围一圈找空位
                double angle = attempt < 3
                    ? forwardAngle + (random.nextDouble() - 0.5D) * 0.7D
                    : random.nextDouble() * Math.PI * 2.0D;
                double distance = 3.5D + random.nextDouble() * 10.0D;
                int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
                int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
                BlockPos pos = findSafePos(level, x, z, player.getBlockY(), 8);
                if (pos == null) {
                    continue;
                }
                net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(pos);
                boolean tooClose = false;
                for (net.minecraft.world.phys.Vec3 other : placed) {
                    if (other.distanceToSqr(center) < 1.0D) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) {
                    continue;
                }
                Entity entity = type.spawn(level, pos, MobSpawnType.EVENT);
                if (entity != null) {
                    placed.add(center);
                    spawned++;
                    success = true;
                }
            }
            if (!success) {
                // 狭窄地形/障碍物多：退化为玩家身边直接生成，保证命令数量准确
                BlockPos fallback = findSafePos(level, player.getBlockX(), player.getBlockZ(),
                    player.getBlockY(), 12);
                if (fallback == null) {
                    fallback = player.blockPosition();
                }
                Entity fallbackEntity = type.spawn(level, fallback, MobSpawnType.EVENT);
                if (fallbackEntity != null) {
                    spawned++;
                }
            }
        }
        if (spawned > 0) {
            invalidateCountCache();
        }
        return spawned;
    }

    /**
     * 在 (x, z) 附近、玩家 y ± verticalRange 内找一个可以站人的方块位置：
     * 脚下实心、两格无碰撞、不挨着水/岩浆。
     */
    private static BlockPos findSafePos(ServerLevel level, int x, int z, int playerY, int verticalRange) {
        level.getChunk(x >> 4, z >> 4);
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // 从玩家高度附近向下/向上找，避免生成在悬崖顶或坑底
        for (int dy = 0; dy <= verticalRange; dy++) {
            for (int sign = 0; sign <= 1; sign++) {
                int y = playerY + (sign == 0 ? dy : -dy);
                BlockPos pos = new BlockPos(x, y, z);
                if (Math.abs(y - surface) > 12) {
                    continue;
                }
                if (isSpawnable(level, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private static boolean isSpawnable(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos.below()).isSolid()) {
            return false;
        }
        for (int i = 0; i < 2; i++) {
            BlockPos check = pos.offset(0, i, 0);
            var state = level.getBlockState(check);
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            if (!state.getCollisionShape(level, check).isEmpty()) {
                return false;
            }
        }
        // 头顶 3 格内有水/岩浆不生成
        for (int i = 2; i <= 3; i++) {
            if (!level.getBlockState(pos.offset(0, i, 0)).getFluidState().isEmpty()) {
                return false;
            }
        }
        return level.isPositionEntityTicking(pos);
    }
}
