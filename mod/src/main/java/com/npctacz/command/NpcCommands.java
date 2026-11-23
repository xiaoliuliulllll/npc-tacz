package com.npctacz.command;

import com.npctacz.config.NpcGlobalConfig;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.arguments.CompoundTagArgument;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.npctacz.ModRegistry;
import com.npctacz.data.NpcSpawnConfigData;
import com.npctacz.data.NpcDifficultyData;
import com.npctacz.entity.NpcFaction;
import com.npctacz.entity.WastelandNpcEntity;
import com.npctacz.entity.ai.NpcReactionTime;
import com.npctacz.event.NpcSpawnEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /tacznpc 指令层（Brigadier 原生指令，全部带 Tab 补全）。
 *
 * <pre>
 * /tacznpc spawn &lt;soldier|bandit|rebel|bounty_hunter|mercenary|all&gt; [数量] [玩家外] [玩家内]  在玩家周围生成
 * /tacznpc clear [半径]          清除附近的 NPC（默认 64 格）
 * /tacznpc count [半径]          统计附近 NPC 数量
 * /tacznpc givenpc|give &lt;类型|all&gt; [数量]  给 NPC 刷怪蛋（all = 每种各 N 个）
 * /tacznpc difficulty [1-4]      查询/设置难度
 * /tacznpc generate_prompt &lt;true|false&gt; 自然生成提示开关
 * /tacznpc spawn_config [类型|all] time|amount|range  查看/修改自然生成规则
 * /tacznpc spawn_config initial_config   恢复默认生成规则
 * /tacznpc npcdebug              最近的 NPC 状态诊断
 * /tacznpc debug_ammo &lt;弹匣&gt; &lt;备弹&gt; 调试：直接设置最近 NPC 的弹药
 * /tacznpc help [chinese|english]
 * </pre>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcCommands {

    private static final org.slf4j.Logger LOGGER =
        com.mojang.logging.LogUtils.getLogger();

    private static final String[] TYPES = {"soldier", "bandit", "rebel", "bounty_hunter", "mercenary", "all"};

    private static final SuggestionProvider<CommandSourceStack> TYPE_SUGGESTIONS =
        (context, builder) -> SharedSuggestionProvider.suggest(TYPES, builder);

    private NpcCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tacznpc")
            .requires(source -> source.hasPermission(2))
            .executes(context -> queryDifficulty(context.getSource()))
            .then(Commands.literal("spawn")
                .then(Commands.argument("type", StringArgumentType.word())
                    .suggests(TYPE_SUGGESTIONS)
                    .executes(context -> spawnCommand(
                        context.getSource(),
                        StringArgumentType.getString(context, "type"),
                        1
                    ))
                    .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                        .executes(context -> spawnCommand(
                            context.getSource(),
                            StringArgumentType.getString(context, "type"),
                            IntegerArgumentType.getInteger(context, "count")
                        ))
                        .then(Commands.argument("outer", IntegerArgumentType.integer(0, 1024))
                            .executes(context -> spawnCommand(
                                context.getSource(),
                                StringArgumentType.getString(context, "type"),
                                IntegerArgumentType.getInteger(context, "count"),
                                IntegerArgumentType.getInteger(context, "outer"),
                                0
                            ))
                            .then(Commands.argument("inner", IntegerArgumentType.integer(0, 1024))
                                .executes(context -> spawnCommand(
                                    context.getSource(),
                                    StringArgumentType.getString(context, "type"),
                                    IntegerArgumentType.getInteger(context, "count"),
                                    IntegerArgumentType.getInteger(context, "outer"),
                                    IntegerArgumentType.getInteger(context, "inner")
                                )))))))
            .then(Commands.literal("clear")
                .executes(context -> clearNpcs(context.getSource(), 64))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                    .executes(context -> clearNpcs(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "radius")
                    ))))
            .then(Commands.literal("count")
                .executes(context -> countNpcs(context.getSource(), 128))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                    .executes(context -> countNpcs(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "radius")
                    ))))
            .then(giveEggsCommand("givenpc"))
            .then(giveEggsCommand("give"))
            .then(Commands.literal("difficulty")
                .executes(context -> queryDifficulty(context.getSource()))
                .then(Commands.argument("level", IntegerArgumentType.integer(1, 4))
                    .executes(context -> setDifficulty(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "level")
                    ))))
            .then(Commands.literal("generate_prompt")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(context -> setGeneratePrompt(
                        context.getSource(),
                        BoolArgumentType.getBool(context, "enabled")
                    ))))
            .then(Commands.literal("npcdebug")
                .executes(context -> npcDebug(context.getSource())))
            .then(Commands.literal("precision")
                .executes(context -> showPrecision(context.getSource())))
            .then(Commands.literal("cadence")
                .then(Commands.argument("mode", StringArgumentType.word())
                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        new String[]{"slow", "normal", "fast"}, builder))
                    .executes(context -> setCadence(
                        context.getSource(),
                        StringArgumentType.getString(context, "mode")
                    ))))
            .then(Commands.literal("debug_ammo")
                .then(Commands.argument("magazine", IntegerArgumentType.integer(0, 9999))
                    .then(Commands.argument("reserve", IntegerArgumentType.integer(0, 99999))
                        .executes(context -> debugAmmo(
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "magazine"),
                            IntegerArgumentType.getInteger(context, "reserve")
                        )))))
            .then(Commands.literal("npcmax")
                .executes(context -> queryNpcMax(context.getSource()))
                .then(Commands.argument("n", IntegerArgumentType.integer(1, Integer.MAX_VALUE))
                    .executes(context -> setNpcMax(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "n")
                    ))))
            .then(Commands.literal("deathloot")
                .executes(context -> queryDeathLoot(context.getSource()))
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(context -> setDeathLoot(
                        context.getSource(),
                        BoolArgumentType.getBool(context, "enabled")
                    ))))
            .then(Commands.literal("occlusion")
                .executes(context -> queryOcclusion(context.getSource()))
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(context -> setOcclusion(
                        context.getSource(),
                        BoolArgumentType.getBool(context, "enabled")
                    ))))
            .then(Commands.literal("reloadconfig").executes(context -> reloadGlobalConfig(context.getSource())))
            .then(Commands.literal("config").executes(context -> showGlobalConfig(context.getSource())))
            .then(Commands.literal("diagnose").executes(context -> diagnose(context.getSource())))
            .then(Commands.literal("selftest").executes(context -> selfTest(context.getSource())))
            .then(Commands.literal("wake")
                .executes(context -> wakeNpcs(context.getSource(), 32))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                    .executes(context -> wakeNpcs(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "radius")
                    ))))
            .then(Commands.literal("targetcreative")
                .executes(context -> queryTargetCreative(context.getSource()))
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(context -> setTargetCreative(
                        context.getSource(),
                        BoolArgumentType.getBool(context, "enabled")
                    ))))
            .then(Commands.literal("weakrun")
                .executes(context -> queryWeakRun(context.getSource()))
                .then(Commands.argument("distance", IntegerArgumentType.integer(16, 4096))
                    .executes(context -> setWeakRun(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "distance")
                    ))))
            .then(Commands.literal("audit")
                .executes(context -> auditNpcs(context.getSource(), 64))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                    .executes(context -> auditNpcs(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "radius")
                    ))))
            .then(Commands.literal("attach")
                .executes(context -> attachGuns(context.getSource(), 32))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                    .executes(context -> attachGuns(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "radius")
                    ))))
            .then(Commands.literal("clean")
                .executes(context -> cleanCorpses(context.getSource(), 128))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                    .executes(context -> cleanCorpses(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "radius")
                    ))))
            .then(Commands.literal("spawnnow")
                .executes(context -> forceSpawnWave(context.getSource(), "all"))
                .then(Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .suggests(SPAWN_CONFIG_SUGGESTIONS)
                    .executes(context -> forceSpawnWave(
                        context.getSource(),
                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "type")
                    ))))
            .then(spawnConfigCommand())
            .then(Commands.literal("help")
                .executes(context -> help(context.getSource(), true))
                .then(Commands.literal("chinese").executes(context -> help(context.getSource(), true)))
                .then(Commands.literal("english").executes(context -> help(context.getSource(), false))));
        event.getDispatcher().register(root);
        // 短别名 /tnpc（同一个指令树）
        try {
            var rootNode = event.getDispatcher().getRoot().getChild("tacznpc");
            if (rootNode != null) {
                event.getDispatcher().getRoot().addChild(Commands.literal("tnpc")
                    .requires(source -> source.hasPermission(2))
                    .redirect(rootNode)
                    .build());
            }
        } catch (Throwable ignored) {
            // 别名注册失败不影响主指令
        }
    }

    // ------------------------------------------------------------------ 全局配置 / 诊断

    private static int reloadGlobalConfig(CommandSourceStack source) {
        try {
            NpcGlobalConfig.load();
            source.sendSuccess(() -> Component.literal(
                "§a已重新读取 §fconfig/" + NpcGlobalConfig.FILE_NAME + " §7（" + NpcGlobalConfig.size() + " 项）"
            ), false);
            if (!NpcGlobalConfig.lastError().isEmpty()) {
                source.sendSuccess(() -> Component.literal("§c读取错误：" + NpcGlobalConfig.lastError()), false);
            }
            source.sendSuccess(() -> Component.literal(
                "§7提示：伤害/瞄准/抖动/视线/补弹类立即生效；npc_max_default、生成类下次生成生效"
            ), false);
            return NpcGlobalConfig.size();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("重载失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int showGlobalConfig(CommandSourceStack source) {
        try {
            source.sendSuccess(() -> Component.literal(
                "§6===== " + NpcGlobalConfig.FILE_NAME + " ====="
            ), false);
            source.sendSuccess(() -> Component.literal(
                "§7路径：" + FMLPaths.CONFIGDIR.get().resolve(NpcGlobalConfig.FILE_NAME)
            ), false);
            if (!NpcGlobalConfig.lastError().isEmpty()) {
                source.sendSuccess(() -> Component.literal("§c错误：" + NpcGlobalConfig.lastError()), false);
            }
            int shown = 0;
            for (var entry : NpcGlobalConfig.snapshot().entrySet()) {
                if (shown++ >= 40) {
                    source.sendSuccess(() -> Component.literal("§7……（只显示前 40 项）"), false);
                    break;
                }
                source.sendSuccess(() -> Component.literal(
                    "§e" + entry.getKey() + " §7= §f" + entry.getValue()
                ), false);
            }
            source.sendSuccess(() -> Component.literal(
                "§7修改文件后执行 /tacznpc reloadconfig 立即生效"
            ), false);
            return NpcGlobalConfig.size();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("读取配置失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int diagnose(CommandSourceStack source) {
        try {
            ServerLevel level = source.getLevel();
            NpcDifficultyData difficultyData = NpcDifficultyData.get(level);
            int cap = difficultyData.getMaxNpcs();
            int total = NpcSpawnEvents.countAllNpcs(source.getServer());
            com.npctacz.entity.WastelandNpcEntity nearest = nearestNpc(source);
            net.minecraft.server.level.ServerPlayer player = source.getPlayer();
            String gameMode = player == null ? "-"
                : player.isCreative() ? "创造" : player.isSpectator() ? "旁观" : "生存";

            source.sendSuccess(() -> Component.literal("§6===== NPC-TACZ 诊断 ====="), false);
            source.sendSuccess(() -> Component.literal(
                "§7世界难度：§f" + level.getDifficulty()
                    + " §7玩家模式：§f" + gameMode
                    + " §7TACZ：§f" + ModList.get().isLoaded("tacz")
            ), false);
            source.sendSuccess(() -> Component.literal(
                "§7NPC 上限：§f" + cap + " §7全服数量：§f" + total
                    + " §7全局配置项：§f" + NpcGlobalConfig.size()
            ), false);
            if (nearest == null) {
                source.sendSuccess(() -> Component.literal(
                    "§e最近 64 格内没有 NPC。可以 /tacznpc spawn soldier 1 生成一个再诊断"
                ), false);
            } else {
                String target = nearest.getTarget() == null ? "无" : nearest.getTarget().getName().getString();
                source.sendSuccess(() -> Component.literal(
                    "§7最近 NPC：§f" + nearest.getType().getDescription().getString()
                        + " §7阵营=§f" + nearest.getFaction()
                        + " §7状态=§f" + nearest.getWeaponState()
                        + " §7血量=§f" + String.format("%.1f", nearest.getHealth())
                        + " §7目标=§f" + target
                        + " §7距离=§f" + String.format("%.1f", Math.sqrt(nearest.distanceToSqr(source.getPosition())))
                ), false);
                source.sendSuccess(() -> Component.literal(
                    "§7枪=§f" + nearest.getGunId()
                        + " §7弹匣=§f" + nearest.getMagazineAmmo()
                        + " §7卡住=§f" + nearest.navigator().isStuck()
                        + " §7弱运行=§f" + nearest.isWeakRunning()
                ), false);
            }
            if (player != null && player.isCreative()) {
                source.sendSuccess(() -> Component.literal(
                    "§e注意：创造模式下 NPC 默认不攻击你；可在 config/" + NpcGlobalConfig.FILE_NAME
                        + " 里把 target_creative_players 改成 true"
                ), false);
            }
            source.sendSuccess(() -> Component.literal(
                "§7全局配置路径：" + FMLPaths.CONFIGDIR.get().resolve(NpcGlobalConfig.FILE_NAME)
                    + (NpcGlobalConfig.lastError().isEmpty() ? "" : "  §c错误：" + NpcGlobalConfig.lastError())
            ), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("诊断失败 / Failed: " + exception));
            return 0;
        }
    }

    // ------------------------------------------------------------------ 唤醒 / 攻击创造玩家

    private static int wakeNpcs(CommandSourceStack source, int radius) {
        try {
            ServerLevel level = source.getLevel();
            Vec3 pos = source.getPosition();
            AABB box = new AABB(pos.x - radius, pos.y - 64.0D, pos.z - radius,
                pos.x + radius, pos.y + 64.0D, pos.z + radius);
            java.util.List<com.npctacz.entity.WastelandNpcEntity> npcs =
                level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class, box);
            for (com.npctacz.entity.WastelandNpcEntity npc : npcs) {
                npc.wakeUp();
            }
            final int finalCount = npcs.size();
            source.sendSuccess(() -> Component.literal(
                "§a已唤醒 " + finalCount + " 个 NPC（清除 NoAI/嘲讽/目标，半径 " + radius + "）"
            ), false);
            LOGGER.info("[npc-tacz] command wake: radius={} npcs={}", radius, finalCount);
            return finalCount;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("唤醒失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int queryTargetCreative(CommandSourceStack source) {
        boolean enabled = NpcGlobalConfig.getBoolean("target_creative_players", false);
        LOGGER.info("[npc-tacz] target_creative_players = {}", enabled);
        source.sendSuccess(() -> Component.literal(
            "§e是否攻击创造模式玩家：§f" + enabled + " §7（/tacznpc targetcreative true|false）"
        ), false);
        return enabled ? 1 : 0;
    }

    private static int setTargetCreative(CommandSourceStack source, boolean enabled) {
        try {
            boolean saved = NpcGlobalConfig.setAndSave("target_creative_players", String.valueOf(enabled));
            if (!saved) {
                source.sendFailure(Component.literal("保存失败 / Failed: " + NpcGlobalConfig.lastError()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                "§a已设置：NPC " + (enabled ? "会" : "不会")
                    + "把创造模式玩家当作目标（立即生效）"
            ), true);
            return enabled ? 1 : 0;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("设置失败 / Failed: " + exception));
            return 0;
        }
    }

    // ------------------------------------------------------------------ 弱运行距离

    private static int queryWeakRun(CommandSourceStack source) {
        int distance = NpcGlobalConfig.getInt("weak_run_distance", 128);
        source.sendSuccess(() -> Component.literal(
            "§e弱运行距离：§f" + distance + " §7格（玩家超过该距离 NPC 进入省性能状态）"
                + "  §7修改：/tacznpc weakrun <16~4096>"
        ), false);
        return distance;
    }

    private static int setWeakRun(CommandSourceStack source, int distance) {
        try {
            boolean saved = NpcGlobalConfig.setAndSave("weak_run_distance", String.valueOf(distance));
            if (!saved) {
                source.sendFailure(Component.literal("保存失败 / Failed: " + NpcGlobalConfig.lastError()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                "§a弱运行距离已设为 §f" + distance + " §7格（已写入 config/"
                    + NpcGlobalConfig.FILE_NAME + "，立即生效）"
            ), true);
            LOGGER.info("[npc-tacz] weakrun set to {}", distance);
            return distance;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("设置失败 / Failed: " + exception));
            return 0;
        }
    }

    // ------------------------------------------------------------------ 装备审计

    private static int auditNpcs(CommandSourceStack source, int radius) {
        try {
            ServerLevel level = source.getLevel();
            Vec3 pos = source.getPosition();
            AABB box = new AABB(pos.x - radius, pos.y - 64.0D, pos.z - radius,
                pos.x + radius, pos.y + 64.0D, pos.z + radius);
            java.util.List<com.npctacz.entity.WastelandNpcEntity> npcs =
                level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class, box);
            int explosiveGuns = 0;
            int explosiveAttachments = 0;
            int missing = 0;
            for (com.npctacz.entity.WastelandNpcEntity npc : npcs) {
                if (com.npctacz.entity.ai.NpcGunRules.isExplosiveId(npc.getGunId())
                    || com.npctacz.entity.ai.NpcGunRules.isExplosiveGun(npc.getGunIndex())) {
                    explosiveGuns++;
                    LOGGER.warn("[npc-tacz] audit explosive gun: {}", npc.getGunId());
                }
                ItemStack hand = npc.getMainHandItem();
                var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(hand);
                if (gun == null) {
                    continue;
                }
                missing += npc.missingAttachmentCount(hand, gun);
                for (com.tacz.guns.api.item.attachment.AttachmentType type
                    : com.tacz.guns.api.item.attachment.AttachmentType.values()) {
                    if (type == com.tacz.guns.api.item.attachment.AttachmentType.NONE) {
                        continue;
                    }
                    var id = gun.getAttachmentId(hand, type);
                    if (id != null
                        && !com.tacz.guns.api.DefaultAssets.isEmptyAttachmentId(id)
                        && com.npctacz.entity.ai.NpcGunRules.isExplosiveAttachmentId(id)) {
                        explosiveAttachments++;
                        LOGGER.warn("[npc-tacz] audit explosive attachment: {} (type={})", id, type);
                    }
                }
            }
            final int finalExplosiveGuns = explosiveGuns;
            final int finalExplosiveAttachments = explosiveAttachments;
            final int finalMissing = missing;
            final int finalCount = npcs.size();
            LOGGER.info("[npc-tacz] command audit: radius={} npcs={} explosiveGuns={} explosiveAttachments={} missingAttachments={}",
                radius, finalCount, finalExplosiveGuns, finalExplosiveAttachments, finalMissing);
            source.sendSuccess(() -> Component.literal(
                "§6===== NPC 装备审计 =====\n"
                    + "§7NPC：§f" + finalCount
                    + " §7爆炸枪：§f" + finalExplosiveGuns
                    + " §7爆炸配件：§f" + finalExplosiveAttachments
                    + " §7缺配件槽：§f" + finalMissing
                    + "\n§7" + (finalExplosiveGuns == 0 && finalExplosiveAttachments == 0
                        ? "§a没有爆炸物装备" : "§c发现爆炸物装备，见日志")
            ), false);
            return finalCount;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("审计失败 / Failed: " + exception));
            return 0;
        }
    }

    // ------------------------------------------------------------------ 满配件修复

    private static int attachGuns(CommandSourceStack source, int radius) {
        try {
            ServerLevel level = source.getLevel();
            Vec3 pos = source.getPosition();
            AABB box = new AABB(pos.x - radius, pos.y - 64.0D, pos.z - radius,
                pos.x + radius, pos.y + 64.0D, pos.z + radius);
            java.util.List<com.npctacz.entity.WastelandNpcEntity> npcs =
                level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class, box);
            int repaired = 0;
            for (com.npctacz.entity.WastelandNpcEntity npc : npcs) {
                repaired += npc.repairGunAttachments();
            }
            final int finalRepaired = repaired;
            final int finalCount = npcs.size();
            LOGGER.info("[npc-tacz] command attach: radius={} npcs={} repaired={}",
                radius, finalCount, finalRepaired);
            source.sendSuccess(() -> Component.literal(
                "§a检查了 " + finalCount + " 个 NPC，补上 " + finalRepaired + " 个配件"
                    + "（半径 " + radius + "）"
            ), false);
            return finalRepaired;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("补配件失败 / Failed: " + exception));
            return 0;
        }
    }

    // ------------------------------------------------------------------ 尸体清理

    private static int cleanCorpses(CommandSourceStack source, int radius) {
        try {
            ServerLevel level = source.getLevel();
            Vec3 pos = source.getPosition();
            AABB box = new AABB(
                pos.x - radius, pos.y - 64.0D, pos.z - radius,
                pos.x + radius, pos.y + 64.0D, pos.z + radius
            );
            java.util.List<com.npctacz.entity.WastelandNpcEntity> corpses =
                level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class, box,
                    npc -> npc.isDeadOrDying() || npc.getHealth() <= 0.0F);
            for (com.npctacz.entity.WastelandNpcEntity npc : corpses) {
                npc.discard();
            }
            NpcSpawnEvents.invalidateCountCache();
            source.sendSuccess(() -> Component.literal(
                "§a已清理 " + corpses.size() + " 具尸体（半径 " + radius + " 格）"
            ), false);
            return corpses.size();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("清理失败 / Failed: " + exception));
            return 0;
        }
    }

    // ------------------------------------------------------------------ 自检 / 立即生成

    private static int selfTest(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("该指令需要玩家执行 / Must be run by a player"));
            return 0;
        }
        try {
            ServerLevel level = (ServerLevel) player.level();
            int cap = NpcDifficultyData.get(level).getMaxNpcs();
            int total = NpcSpawnEvents.countAllNpcs(level.getServer());
            java.util.List<com.npctacz.entity.WastelandNpcEntity> nearby =
                level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class,
                    player.getBoundingBox().inflate(24.0D));
            com.npctacz.entity.WastelandNpcEntity npc = nearby.stream()
                .min(java.util.Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
            if (npc == null) {
                int spawned = NpcSpawnEvents.spawnForCommand(
                    level, player, ModRegistry.SOLDIER.get(), 1, false);
                if (spawned <= 0) {
                    source.sendFailure(Component.literal(
                        "§c附近没有 NPC，且无法生成（上限 " + cap + "，当前 " + total + "）"
                    ));
                    return 0;
                }
                npc = level.getEntitiesOfClass(com.npctacz.entity.WastelandNpcEntity.class,
                        player.getBoundingBox().inflate(24.0D)).stream()
                    .min(java.util.Comparator.comparingDouble(player::distanceToSqr))
                    .orElse(null);
            }
            if (npc == null) {
                source.sendFailure(Component.literal("§c生成后仍未找到 NPC，请查 latest.log"));
                return 0;
            }
            final com.npctacz.entity.WastelandNpcEntity target = npc;
            float before = target.getHealth();
            boolean hurt = target.hurt(player.damageSources().playerAttack(player), 5.0F);
            float after = target.getHealth();
            source.sendSuccess(() -> Component.literal(
                "§6===== NPC 自检 =====\n"
                    + "§7目标：§f" + target.getType().getDescription().getString()
                    + " §7距离：§f" + String.format("%.1f", Math.sqrt(player.distanceToSqr(target)))
                    + "\n§7血量：§f" + before + " §7→ §f" + after
                    + " §7hurt=§f" + hurt
                    + " §7无敌=§f" + target.isInvulnerable()
                    + " §7存活=§f" + target.isAlive()
                    + "\n§7结论：" + (hurt && after < before
                        ? "§a玩家伤害正常，NPC 可被击中"
                        : "§c伤害没有生效，请把这条和 latest.log 发给作者")
            ), false);
            return hurt ? 1 : 0;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("自检失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int forceSpawnWave(CommandSourceStack source, String typeId) {
        try {
            NpcFaction faction = null;
            if (!"all".equalsIgnoreCase(typeId)) {
                for (NpcFaction value : NpcFaction.values()) {
                    if (value.name().equalsIgnoreCase(typeId)) {
                        faction = value;
                        break;
                    }
                }
                if (faction == null) {
                    source.sendFailure(Component.literal("未知 NPC 类型 / Unknown type: " + typeId));
                    return 0;
                }
            }
            final NpcFaction targetFaction = faction;
            int spawned = NpcSpawnEvents.forceSpawnWave(source.getServer(), targetFaction);
            ServerLevel level = source.getLevel();
            int cap = NpcDifficultyData.get(level).getMaxNpcs();
            int total = NpcSpawnEvents.countAllNpcs(source.getServer());
            source.sendSuccess(() -> Component.literal(
                "§a立即生成：" + spawned + " 个"
                    + (targetFaction == null ? "（全阵营）" : "（" + targetFaction + "）")
                    + "  §7上限 " + cap + "，当前 " + total
            ), false);
            return spawned;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("生成失败 / Failed: " + exception));
            return 0;
        }
    }

    // ------------------------------------------------------------------ NPC 上限

    private static int queryNpcMax(CommandSourceStack source) {
        try {
            int cap = NpcDifficultyData.get(source.getLevel()).getMaxNpcs();
            int current = NpcSpawnEvents.countAllNpcs(source.getServer());
            source.sendSuccess(() -> Component.literal(
                "§eNPC 上限：§f" + cap + " §7（当前全服 NPC：§f" + current + "§7）"
                    + "  §7用 /tacznpc npcmax <n> 修改，范围 1~2147483647"
            ), false);
            return cap;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("查询失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int setNpcMax(CommandSourceStack source, int n) {
        try {
            int cap = Math.max(1, n);
            NpcDifficultyData.get(source.getLevel()).setMaxNpcs(cap);
            int current = NpcSpawnEvents.countAllNpcs(source.getServer());
            source.sendSuccess(() -> Component.literal(
                "§aNPC 上限已设为 §f" + cap + " §7（当前全服 NPC：§f" + current + "§7）"
            ), true);
            return cap;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("设置失败 / Failed: " + exception));
            return 0;
        }
    }

    // ------------------------------------------------------------------ 生成配置

    private static final String[] SPAWN_CONFIG_NPCS =
        {"soldier", "bandit", "rebel", "mercenary", "bounty_hunter", "all"};

    private static final java.util.Map<String, String> SPAWN_CONFIG_CHINESE = java.util.Map.of(
        "soldier", "士兵",
        "bandit", "劫匪",
        "rebel", "叛军",
        "mercenary", "雇佣兵",
        "bounty_hunter", "赏金猎人",
        "all", "全部类型"
    );

    private static final SuggestionProvider<CommandSourceStack> SPAWN_CONFIG_SUGGESTIONS =
        (context, builder) -> {
            for (String id : SPAWN_CONFIG_NPCS) {
                builder.suggest(id, Component.literal(SPAWN_CONFIG_CHINESE.getOrDefault(id, id)));
            }
            return builder.buildFuture();
        };

    private static LiteralArgumentBuilder<CommandSourceStack> spawnConfigCommand() {
        return Commands.literal("spawn_config")
            .executes(context -> showSpawnConfig(context.getSource()))
            .then(Commands.literal("initial_config").executes(context -> resetSpawnConfig(context.getSource())))
            .then(Commands.argument("npc", StringArgumentType.word())
                .suggests(SPAWN_CONFIG_SUGGESTIONS)
                .then(Commands.literal("time")
                    .then(Commands.argument("value", StringArgumentType.word())
                        .executes(context -> setSpawnTime(
                            context.getSource(),
                            StringArgumentType.getString(context, "npc"),
                            StringArgumentType.getString(context, "value")
                        ))))
                .then(Commands.literal("amount")
                    .then(Commands.argument("min", IntegerArgumentType.integer(0, 64))
                        .executes(context -> setSpawnAmount(
                            context.getSource(),
                            StringArgumentType.getString(context, "npc"),
                            IntegerArgumentType.getInteger(context, "min"),
                            IntegerArgumentType.getInteger(context, "min")
                        ))
                        .then(Commands.argument("max", IntegerArgumentType.integer(0, 64))
                            .executes(context -> setSpawnAmount(
                                context.getSource(),
                                StringArgumentType.getString(context, "npc"),
                                IntegerArgumentType.getInteger(context, "min"),
                                IntegerArgumentType.getInteger(context, "max")
                            )))))
                .then(Commands.literal("range")
                    .then(Commands.argument("outer", IntegerArgumentType.integer(16, 1024))
                        .then(Commands.argument("inner", IntegerArgumentType.integer(16, 1024))
                            .executes(context -> setSpawnRange(
                                context.getSource(),
                                StringArgumentType.getString(context, "npc"),
                                IntegerArgumentType.getInteger(context, "outer"),
                                IntegerArgumentType.getInteger(context, "inner")
                            ))))));
    }

    private static int showSpawnConfig(CommandSourceStack source) {
        try {
            NpcSpawnConfigData config = NpcSpawnConfigData.get(source.getLevel());
            source.sendSuccess(() -> Component.literal("§6===== NPC 生成配置 ====="), false);
            for (NpcFaction faction : NpcFaction.values()) {
                NpcSpawnConfigData.Rule rule = config.rule(faction);
                String line = "§e" + SPAWN_CONFIG_CHINESE.getOrDefault(faction.name().toLowerCase(Locale.ROOT), faction.name())
                    + " §7间隔=§f" + NpcSpawnConfigData.formatInterval(rule.intervalTicks)
                    + " §7数量=§f" + rule.amountMin + "~" + rule.amountMax
                    + " §7范围=§f" + (int) rule.innerRadius + "~" + (int) rule.outerRadius + "格";
                source.sendSuccess(() -> Component.literal(line), false);
            }
            source.sendSuccess(() -> Component.literal(
                "§7修改：/tacznpc spawn_config <类型|all> time <10m|3d|30s> ; amount <min> [max] ; range <外> <内>"
            ), false);
            source.sendSuccess(() -> Component.literal(
                "§7重置：/tacznpc spawn_config initial_config"
            ), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("读取配置失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int resetSpawnConfig(CommandSourceStack source) {
        try {
            NpcSpawnConfigData.get(source.getLevel()).resetDefaults();
            NpcSpawnEvents.onConfigChanged();
            source.sendSuccess(() -> Component.literal("§a已恢复默认生成配置（10分钟 3~5士兵 / 4~6劫匪 / 2~3叛军，3天1雇佣兵，5天1赏金猎人）"), true);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("重置失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int setSpawnTime(CommandSourceStack source, String npcId, String value) {
        try {
            long ticks = parseTicks(value);
            NpcSpawnConfigData config = NpcSpawnConfigData.get(source.getLevel());
            int changed = 0;
            for (NpcFaction faction : resolveSpawnFactions(npcId)) {
                config.setInterval(faction, ticks);
                changed++;
            }
            if (changed == 0) {
                source.sendFailure(Component.literal("§c未知 NPC 类型：" + npcId));
                return 0;
            }
            NpcSpawnEvents.onConfigChanged();
            source.sendSuccess(() -> Component.literal(
                "§a已设置 " + npcId + " 刷新间隔：§f" + NpcSpawnConfigData.formatInterval(ticks)
            ), true);
            return changed;
        } catch (NumberFormatException exception) {
            source.sendFailure(Component.literal("§c时间格式错误，示例：10（分钟）、30s、3d、2h"));
            return 0;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("设置失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int setSpawnAmount(CommandSourceStack source, String npcId, int min, int max) {
        try {
            if (max < min) {
                source.sendFailure(Component.literal("§c最大数量不能小于最小数量"));
                return 0;
            }
            NpcSpawnConfigData config = NpcSpawnConfigData.get(source.getLevel());
            int changed = 0;
            for (NpcFaction faction : resolveSpawnFactions(npcId)) {
                config.setAmount(faction, min, max);
                changed++;
            }
            if (changed == 0) {
                source.sendFailure(Component.literal("§c未知 NPC 类型：" + npcId));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                "§a已设置 " + npcId + " 每次生成数量：§f" + min + "~" + max
            ), true);
            return changed;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("设置失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int setSpawnRange(CommandSourceStack source, String npcId, int outer, int inner) {
        try {
            if (inner >= outer) {
                source.sendFailure(Component.literal("§c内圈必须小于外圈（外>内）"));
                return 0;
            }
            if (inner < 16 || outer > 1024) {
                source.sendFailure(Component.literal("§c范围必须在 16~1024 之间"));
                return 0;
            }
            NpcSpawnConfigData config = NpcSpawnConfigData.get(source.getLevel());
            int changed = 0;
            for (NpcFaction faction : resolveSpawnFactions(npcId)) {
                config.setRange(faction, outer, inner);
                changed++;
            }
            if (changed == 0) {
                source.sendFailure(Component.literal("§c未知 NPC 类型：" + npcId));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                "§a已设置 " + npcId + " 生成范围：玩家外 §f" + outer + " §a格 内 §f" + inner + " §a格"
            ), true);
            return changed;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("设置失败 / Failed: " + exception));
            return 0;
        }
    }

    private static java.util.List<NpcFaction> resolveSpawnFactions(String npcId) {
        if ("all".equalsIgnoreCase(npcId)) {
            return java.util.List.of(NpcFaction.values());
        }
        for (NpcFaction faction : NpcFaction.values()) {
            if (faction.name().toLowerCase(Locale.ROOT).equalsIgnoreCase(npcId)) {
                return java.util.List.of(faction);
            }
        }
        return java.util.List.of();
    }

    /** 时间解析：10 = 10 分钟；30s / 2h / 3d 也支持。 */
    private static long parseTicks(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        long multiplier = NpcSpawnConfigData.TICKS_PER_MINUTE;
        char last = text.charAt(text.length() - 1);
        boolean hasSuffix = last == 's' || last == 'm' || last == 'h' || last == 'd';
        if (hasSuffix) {
            multiplier = switch (last) {
                case 's' -> NpcSpawnConfigData.TICKS_PER_SECOND;
                case 'm' -> NpcSpawnConfigData.TICKS_PER_MINUTE;
                case 'h' -> 60L * NpcSpawnConfigData.TICKS_PER_MINUTE;
                case 'd' -> NpcSpawnConfigData.TICKS_PER_DAY;
                default -> multiplier;
            };
            text = text.substring(0, text.length() - 1);
        }
        double amount = Double.parseDouble(text);
        long ticks = Math.round(amount * multiplier);
        long max = 365L * NpcSpawnConfigData.TICKS_PER_DAY;
        return Math.max(NpcSpawnConfigData.TICKS_PER_SECOND, Math.min(max, ticks));
    }

    // ------------------------------------------------------------------ 生成

    /** /tacznpc givenpc | give | egg <类型> [数量] 共用子树。 */
    private static LiteralArgumentBuilder<CommandSourceStack> giveEggsCommand(String literal) {
        return Commands.literal(literal)
            .then(Commands.argument("type", StringArgumentType.word())
                .suggests(TYPE_SUGGESTIONS)
                .executes(context -> giveNpcEggs(
                    context.getSource(),
                    StringArgumentType.getString(context, "type"),
                    1
                ))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                    .executes(context -> giveNpcEggs(
                        context.getSource(),
                        StringArgumentType.getString(context, "type"),
                        IntegerArgumentType.getInteger(context, "count")
                    ))));
    }

    private static int spawnCommand(CommandSourceStack source, String typeId, int count) {
        return spawnCommand(source, typeId, count, -1, -1);
    }

    private static int spawnCommand(CommandSourceStack source, String typeId, int count,
                                    int outerRadius, int innerRadius) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("该指令需要玩家执行 / Must be run by a player"));
            return 0;
        }
        ServerLevel level = (ServerLevel) player.level();
        String id = typeId.toLowerCase();
        if ("all".equals(id)) {
            int[] perType = splitCount(count);
            int total = 0;
            total += NpcSpawnEvents.spawnForCommand(level, player, ModRegistry.SOLDIER.get(), perType[0], false, outerRadius, innerRadius);
            total += NpcSpawnEvents.spawnForCommand(level, player, ModRegistry.BANDIT.get(), perType[1], false, outerRadius, innerRadius);
            total += NpcSpawnEvents.spawnForCommand(level, player, ModRegistry.REBEL.get(), perType[2], true, outerRadius, innerRadius);
            total += NpcSpawnEvents.spawnForCommand(level, player, ModRegistry.BOUNTY_HUNTER.get(), perType[3], true, outerRadius, innerRadius);
            total += NpcSpawnEvents.spawnForCommand(level, player, ModRegistry.MERCENARY.get(), perType[4], false, outerRadius, innerRadius);
            final int finalTotal = total;
            source.sendSuccess(() -> Component.literal(
                "§a已生成 " + finalTotal + " 个 NPC（all 混合）"
            ), false);
            return total;
        }
        EntityType<?> type = resolveType(id);
        if (type == null) {
            source.sendFailure(Component.literal("未知 NPC 类型 / Unknown type: " + typeId));
            return 0;
        }
        boolean farGroup = "rebel".equals(id) || "bounty_hunter".equals(id);
        int spawned = NpcSpawnEvents.spawnForCommand(level, player, type, count, farGroup,
            outerRadius, innerRadius);
        if (spawned < count) {
            int cap = com.npctacz.data.NpcDifficultyData.get(level).getMaxNpcs();
            int current = NpcSpawnEvents.countAllNpcs(level.getServer());
            String reason = current >= cap
                ? "§eNPC 上限 " + cap + "，当前 " + current + " 个，已跳过剩余生成数"
                : "§e生成位置不足，只生成了 " + spawned + "/" + count + " 个";
            source.sendSuccess(() -> Component.literal(reason), false);
        }
        LOGGER.info("[npc-tacz] command spawn: type={} requested={} spawned={}",
            id, count, spawned);
        source.sendSuccess(() -> Component.literal(
            "§a已生成 " + spawned + "/" + count + " 个 " + type.getDescription().getString()
        ), false);
        return spawned;
    }

    private static int showPrecision(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("该指令需要玩家执行 / Must be run by a player"));
            return 0;
        }
        var npc = ((ServerLevel) player.level()).getEntitiesOfClass(
            com.npctacz.entity.WastelandNpcEntity.class,
            player.getBoundingBox().inflate(64.0D)).stream()
            .min(java.util.Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (npc == null) {
            source.sendFailure(Component.literal("§e64 格内没有 NPC"));
            return 0;
        }
        var ammo = npc.ammo();
        source.sendSuccess(() -> Component.literal(
            "§6===== 精度状态 =====\n"
            + "§7NPC：§f" + npc.getFaction() + " §7目标：§f"
            + (npc.getTarget() == null ? "无" : npc.getTarget().getName().getString())
            + "\n§7精度：§f" + String.format(java.util.Locale.ROOT, "%.2f", ammo.accuracy())
            + " §7停火恢复：§f" + ammo.accuracyPauseTicks() + "t"
            + "\n§7规则：每枪按难度扣 0.02~0.05；<0.4 加散布；<=0.2 远距离停火 10~20t"
        ), false);
        return 1;
    }

    private static int setCadence(CommandSourceStack source, String mode) {
        int pause;
        int reaction;
        boolean continuous;
        String normalized = mode == null ? "normal" : mode.toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "slow" -> {
                pause = 50;
                reaction = 10;
                continuous = false;
            }
            case "fast" -> {
                pause = 8;
                reaction = 4;
                continuous = true;
            }
            default -> {
                pause = 30;
                reaction = 6;
                continuous = false;
            }
        }
        com.npctacz.config.NpcConfig.VALUES.burstPauseTicks.set(pause);
        com.npctacz.config.NpcConfig.VALUES.reactionTicks.set(reaction);
        com.npctacz.config.NpcConfig.VALUES.continuousFireOnPlayers.set(continuous);
        try {
            com.npctacz.config.NpcConfig.SPEC.save();
        } catch (Throwable ignored) {
        }
        source.sendSuccess(() -> Component.literal(
            "§a攻击节奏：§f" + normalized + " §7(反应 " + reaction + "t / 点射停顿 " + pause + "t)"
        ), true);
        return 1;
    }

    private static int queryOcclusion(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        boolean enabled = com.npctacz.data.NpcDifficultyData.get(level).isVisionOccludersEnabled();
        source.sendSuccess(() -> Component.literal(
            "§6遮挡物：§f" + (enabled ? "开（玻璃/树叶挡视线）" : "关（可看穿玻璃/树叶）")
        ), false);
        return 1;
    }

    private static int setOcclusion(CommandSourceStack source, boolean enabled) {
        com.npctacz.data.NpcDifficultyData.get(source.getLevel()).setVisionOccludersEnabled(enabled);
        source.sendSuccess(() -> Component.literal(
            "§a遮挡物已" + (enabled ? "开启：玻璃/树叶挡住视线" : "关闭：玻璃/树叶可看穿")
        ), true);
        return 1;
    }

    private static int queryDeathLoot(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        boolean enabled = com.npctacz.data.NpcDifficultyData.get(level).isDeathLootContainer();
        source.sendSuccess(() -> Component.literal(
            "§6死亡容器：§f" + (enabled ? "开（掉落进桶）" : "关（普通掉落）")
        ), false);
        return 1;
    }

    private static int setDeathLoot(CommandSourceStack source, boolean enabled) {
        com.npctacz.data.NpcDifficultyData.get(source.getLevel()).setDeathLootContainer(enabled);
        source.sendSuccess(() -> Component.literal(
            "§a死亡容器已" + (enabled ? "开启：NPC死亡掉落会装进桶" : "关闭：恢复普通掉落")
        ), true);
        return 1;
    }

    private static int[] splitCount(int total) {
        int[] per = new int[5];
        for (int i = 0; i < total; i++) {
            per[i % 5]++;
        }
        return per;
    }

    @Nullable
    private static EntityType<?> resolveType(String id) {
        return switch (id) {
            case "soldier" -> ModRegistry.SOLDIER.get();
            case "bandit" -> ModRegistry.BANDIT.get();
            case "rebel" -> ModRegistry.REBEL.get();
            case "bounty_hunter", "bountyhunter", "hunter" -> ModRegistry.BOUNTY_HUNTER.get();
            case "mercenary", "merc" -> ModRegistry.MERCENARY.get();
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 清理 / 统计

    private static int clearNpcs(CommandSourceStack source, int radius) {
        NpcSpawnEvents.clearPending();
        try {
            ServerLevel level = source.getLevel();
            Vec3 pos = source.getPosition();
            AABB box = new AABB(
                pos.x - radius, pos.y - 64.0D, pos.z - radius,
                pos.x + radius, pos.y + 64.0D, pos.z + radius
            );
            List<WastelandNpcEntity> list = level.getEntitiesOfClass(WastelandNpcEntity.class, box);
            for (WastelandNpcEntity npc : list) {
                npc.discard();
            }
            source.sendSuccess(() -> Component.literal("§a已清除 " + list.size() + " 个 NPC"), false);
            return list.size();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("清除失败 / clear failed: " + exception));
            return 0;
        }
    }

    private static int countNpcs(CommandSourceStack source, int radius) {
        try {
            ServerLevel level = source.getLevel();
            Vec3 pos = source.getPosition();
            AABB box = new AABB(
                pos.x - radius, pos.y - 64.0D, pos.z - radius,
                pos.x + radius, pos.y + 64.0D, pos.z + radius
            );
            List<WastelandNpcEntity> list = level.getEntitiesOfClass(WastelandNpcEntity.class, box);
            int ranged = 0;
            int melee = 0;
            int weak = 0;
            for (WastelandNpcEntity npc : list) {
                if (npc.isMeleeState()) {
                    melee++;
                } else {
                    ranged++;
                }
                if (npc.isWeakRunning()) {
                    weak++;
                }
            }
            int finalRanged = ranged;
            int finalMelee = melee;
            int finalWeak = weak;
            LOGGER.info("[npc-tacz] command count: radius={} total={} ranged={} melee={} weak={}",
                radius, list.size(), finalRanged, finalMelee, finalWeak);
            source.sendSuccess(() -> Component.literal(
                "§a半径 " + radius + " 格内 NPC：" + list.size()
                    + "（远程 " + finalRanged + " / 近战 " + finalMelee + " / 弱运行 " + finalWeak + "）"
            ), false);
            return list.size();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("统计失败 / count failed: " + exception));
            return 0;
        }
    }

    // ------------------------------------------------------------------ 诊断

    private static int npcDebug(CommandSourceStack source) {
        try {
            WastelandNpcEntity nearest = nearestNpc(source);
            if (nearest == null) {
                source.sendFailure(Component.literal("64 格内没有 NPC / No NPC nearby"));
                return 0;
            }
            ItemStack hand = nearest.getMainHandItem();
            Vec3 pos = source.getPosition();
            double distance = Math.sqrt(nearest.distanceToSqr(pos));
            StringBuilder attachments = new StringBuilder();
            int missingAllowed = 0;
            try {
                var gunItem = com.tacz.guns.api.item.IGun.getIGunOrNull(hand);
                if (gunItem != null) {
                    for (com.tacz.guns.api.item.attachment.AttachmentType type
                        : com.tacz.guns.api.item.attachment.AttachmentType.values()) {
                        if (type == com.tacz.guns.api.item.attachment.AttachmentType.NONE) {
                            continue;
                        }
                        if (!gunItem.allowAttachmentType(hand, type)) {
                            continue;
                        }
                        var attachmentId = gunItem.getAttachmentId(hand, type);
                        if (attachmentId == null
                            || com.tacz.guns.api.DefaultAssets.isEmptyAttachmentId(attachmentId)) {
                            missingAllowed++;
                            attachments.append(type.name()).append("=空 ");
                        } else {
                            attachments.append(type.name()).append('=')
                                .append(attachmentId).append(' ');
                        }
                    }
                }
            } catch (Throwable ignored) {
                attachments.append("读取失败");
            }
            net.minecraft.world.entity.player.Player nearestPlayer = null;
            if (source.getServer() != null) {
                for (net.minecraft.server.level.ServerPlayer serverPlayer
                    : source.getServer().getPlayerList().getPlayers()) {
                    if (nearestPlayer == null || serverPlayer.distanceToSqr(nearest)
                        < nearestPlayer.distanceToSqr(nearest)) {
                        nearestPlayer = serverPlayer;
                    }
                }
            }
            final net.minecraft.world.entity.player.Player debugPlayer = nearestPlayer;
            final boolean debugHostile = debugPlayer != null && nearest.isHostileTo(debugPlayer);
            final boolean debugLos = debugPlayer != null
                && com.npctacz.entity.ai.NpcCombatHelper.canSee(nearest, debugPlayer);
            String target = nearest.getTarget() == null
                ? "无" : nearest.getTarget().getName().getString();
            String targetDistance = nearest.getTarget() == null
                ? "-" : String.format("%.1f", Math.sqrt(nearest.distanceToSqr(nearest.getTarget())));
            String reaction = nearest.getTarget() == null
                ? "-" : NpcReactionTime.requiredTicks(
                    nearest, nearest.getTarget(), Math.sqrt(nearest.distanceToSqr(nearest.getTarget()))) + "t";
            String message = "§eNPC=" + nearest.getType().toString()
                + " §7阵营=" + nearest.getFaction()
                + " §7距离=" + String.format("%.1f", distance)
                + " §7状态=" + nearest.getWeaponState()
                + " §7弹匣=" + nearest.getMagazineAmmo()
                + " §7备弹=" + nearest.getAmmo()
                + " §7枪=" + nearest.getGunId()
                + " §7手持=" + hand.getItem()
                + " §7目标=" + target
                + " §7目标距离=" + targetDistance
                + " §7反应=" + reaction
                + " §7卡住=" + nearest.navigator().isStuck()
                + " §7weak=" + nearest.isWeakRunning()
                + " §7NoAI=" + nearest.isNoAi()
                + " §7Pos=" + nearest.blockPosition().toShortString()
                + " §7HostilePlayer=" + debugHostile
                + " §7LOS=" + debugLos
                + " §7ScanR=" + String.format("%.0f", nearest.getTargetScanRange())
                + " §7tick=" + nearest.tickCount
                + " §7拾取=" + nearest.getPickupItemCount()
                + " §7HP=" + String.format("%.1f", nearest.getHealth()) + "/" + String.format("%.0f", nearest.getMaxHealth())
                + " §7alive=" + nearest.isAlive()
                + " §7removed=" + nearest.isRemoved()
                + " §7reason=" + nearest.getRemovalReason()
                + " §7Model=" + (nearest.getModelId().isEmpty() ? "无" : nearest.getModelId())
                + (nearest.hasExplicitModel() ? "(单体)" : "(默认)");
            LOGGER.info("[npc-tacz] npcdebug: {}", message);
            final String attachmentText = attachments.toString().trim();
            final int finalMissingAllowed = missingAllowed;
            LOGGER.info("[npc-tacz] npcdebug attachments: {} missing={}",
                attachmentText.isEmpty() ? "无" : attachmentText, finalMissingAllowed);
            source.sendSuccess(() -> Component.literal(
                "§7配件=" + (attachmentText.isEmpty() ? "无" : attachmentText)
                    + (finalMissingAllowed == 0 ? " §a(已满)" : " §e(缺 " + finalMissingAllowed + " 个)")
            ), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("诊断失败 / debug failed: " + exception));
            return 0;
        }
    }

    /** 调试：直接设置最近一只 NPC 的弹匣/备弹（测试弹药耗尽→近战→补弹→切回）。 */
    private static int debugAmmo(CommandSourceStack source, int magazine, int reserve) {
        try {
            WastelandNpcEntity nearest = nearestNpc(source);
            if (nearest == null) {
                source.sendFailure(Component.literal("64 格内没有 NPC / No NPC nearby"));
                return 0;
            }
            final WastelandNpcEntity npcRef = nearest;
            npcRef.ammo().setReserve(reserve);
            npcRef.ammo().debugSetMagazine(magazine);
            source.sendSuccess(() -> Component.literal(
                "§a已设置：弹匣=" + magazine + " 备弹=" + reserve
                    + " 状态=" + npcRef.getWeaponState()
            ), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("设置失败 / debug failed: " + exception));
            return 0;
        }
    }

    @Nullable
    private static WastelandNpcEntity nearestNpc(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        AABB box = new AABB(
            pos.x - 64.0D, pos.y - 32.0D, pos.z - 64.0D,
            pos.x + 64.0D, pos.y + 32.0D, pos.z + 64.0D
        );
        WastelandNpcEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (WastelandNpcEntity npc : level.getEntitiesOfClass(WastelandNpcEntity.class, box)) {
            double distance = npc.distanceToSqr(pos);
            if (distance < best) {
                best = distance;
                nearest = npc;
            }
        }
        return nearest;
    }

    // ------------------------------------------------------------------ 提示 / 难度

    private static int setGeneratePrompt(CommandSourceStack source, boolean enabled) {
        try {
            ServerLevel level = source.getLevel();
            NpcDifficultyData data = NpcDifficultyData.get(level);
            data.setGeneratePrompt(enabled);
            source.sendSuccess(() -> Component.literal(
                "§a生成提示已" + (enabled ? "开启" : "关闭")
                    + " / Spawn notices " + (enabled ? "enabled" : "disabled")
            ), true);
            return enabled ? 1 : 0;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("设置失败 / Failed"));
            return 0;
        }
    }

    private static int giveNpcEggs(CommandSourceStack source, String typeId, int count) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            List<ItemStack> stacks = new ArrayList<>();
            String id = typeId.toLowerCase();
            if ("all".equals(id)) {
                stacks.add(new ItemStack(ModRegistry.SOLDIER_ITEM.get(), count));
                stacks.add(new ItemStack(ModRegistry.BANDIT_ITEM.get(), count));
                stacks.add(new ItemStack(ModRegistry.REBEL_ITEM.get(), count));
                stacks.add(new ItemStack(ModRegistry.BOUNTY_HUNTER_ITEM.get(), count));
                stacks.add(new ItemStack(ModRegistry.MERCENARY_ITEM.get(), count));
            } else {
                ItemStack stack = switch (id) {
                    case "soldier" -> new ItemStack(ModRegistry.SOLDIER_ITEM.get(), count);
                    case "bandit" -> new ItemStack(ModRegistry.BANDIT_ITEM.get(), count);
                    case "rebel" -> new ItemStack(ModRegistry.REBEL_ITEM.get(), count);
                    case "bounty_hunter", "bountyhunter", "hunter" ->
                        new ItemStack(ModRegistry.BOUNTY_HUNTER_ITEM.get(), count);
                    case "mercenary", "merc" -> new ItemStack(ModRegistry.MERCENARY_ITEM.get(), count);
                    default -> null;
                };
                if (stack == null) {
                    source.sendFailure(Component.literal("未知 NPC 类型 / Unknown type: " + typeId));
                    return 0;
                }
                stacks.add(stack);
            }
            for (ItemStack stack : stacks) {
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
            final boolean all = "all".equals(id);
            source.sendSuccess(() -> Component.literal(
                all
                    ? "§a已给予 §f" + count + " §a个 × 每种类型的刷怪蛋（士兵/劫匪/叛军/赏金猎人/雇佣兵，共 5 种）"
                    : "§a已给予 §f" + count + " §a个 §e" + typeId + " §a刷怪蛋"
            ), false);
            return count;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("给予失败 / Failed: " + exception));
            return 0;
        }
    }

    private static int queryDifficulty(CommandSourceStack source) {
        try {
            int value = NpcDifficultyData.get(source.getLevel()).getDifficulty();
            source.sendSuccess(() -> Component.literal(
                "§e当前 NPC 难度：" + value + " / Current NPC difficulty: " + value
                    + "§7（1~4，用 /tacznpc difficulty <1-4> 修改）"
            ), false);
        } catch (Exception exception) {
            source.sendFailure(Component.literal("查询失败 / Failed"));
        }
        return 0;
    }

    private static int setDifficulty(CommandSourceStack source, int value) {
        try {
            NpcDifficultyData.get(source.getLevel()).setDifficulty(value);
            source.sendSuccess(() -> Component.literal(
                "§aNPC 难度已设为 " + value + " / NPC difficulty set to " + value
            ), true);
            return value;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("设置失败 / Failed"));
            return 0;
        }
    }

    private static int help(CommandSourceStack source, boolean chinese) {
        if (chinese) {
            source.sendSuccess(() -> Component.literal("§6===== /tacznpc 指令 ====="), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn <类型> [数量] [玩家外] [玩家内] §7生成 NPC（距离 0~1024）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc clear [半径] §7清除附近 NPC（默认 64 格）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc count [半径] §7统计附近 NPC 数量"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc givenpc|give <类型|all> [数量] §7给刷怪蛋（all=每种各 N 个）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc difficulty [1-4] §7查询/设置难度"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc generate_prompt <true|false> §7自然生成提示开关"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config §7查看自然生成配置"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config <类型|all> time <10m|3d|30s> §7修改刷新间隔"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config <类型|all> amount <min> [max] §7修改每次数量"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config <类型|all> range <外圈> <内圈> §7修改生成范围"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config initial_config §7恢复默认生成配置"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc npcmax [n] §7独立 NPC 总数上限（默认 32，范围 1~2147483647）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc diagnose §7一键诊断（难度/玩家模式/上限/NPC/配置/Mod）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc config §7查看 tacz-npc-modif 全局配置"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc reloadconfig §7重读 tacz-npc-modif"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc selftest §7生成/找最近 NPC 并测试 5 点伤害（排查打不到）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawnnow [类型|all] §7立即按自然规则刷一波（排查不自然生成）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc clean [半径] §7清理死亡后残留的尸体（默认 128 格）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc attach [半径] §7给范围内 NPC 的枪补齐配件（默认 32 格）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc audit [半径] §7审计范围内 NPC 的爆炸物装备/缺配件（默认 64 格）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc weakrun [16~4096] §7查看/设置弱运行距离（默认 128 格）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc wake [半径] §7唤醒范围内被冻结的 NPC（默认 32 格）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc targetcreative [true|false] §7是否攻击创造模式玩家"), false);
            source.sendSuccess(() -> Component.literal("§7短别名：/tnpc（等同 /tacznpc）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc npcdebug §7最近 NPC 状态诊断（含状态/弹药/反应/模型）"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc debug_ammo <弹匣> <备弹> §7调试最近 NPC 的弹药"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc help [chinese|english]"), false);
            source.sendSuccess(() -> Component.literal("§7类型：soldier(士兵) / bandit(劫匪) / rebel(叛军) / bounty_hunter(赏金猎人) / mercenary(雇佣兵) / all"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§6===== /tacznpc ====="), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn <type> [count] [outer] [inner] §7spawn NPCs (distance 0~1024)"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc clear [radius] §7remove nearby NPCs"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc count [radius] §7count nearby NPCs"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc givenpc|give <type|all> [count] §7give spawn eggs (all = N of each)"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc difficulty [1-4] §7query/set difficulty"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc generate_prompt <true|false> §7spawn notices"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config §7show natural spawn config"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config <type|all> time <10m|3d|30s> §7set interval"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config <type|all> amount <min> [max] §7set amount"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config <type|all> range <outer> <inner> §7set range"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc spawn_config initial_config §7restore defaults"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc npcmax [n] §7independent NPC cap (default 32, 1~2147483647)"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc npcdebug §7nearest NPC state (state/ammo/reaction/model)"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc debug_ammo <mag> <reserve> §7debug nearest NPC ammo"), false);
            source.sendSuccess(() -> Component.literal("§e/tacznpc help [chinese|english]"), false);
            source.sendSuccess(() -> Component.literal("§7types: soldier / bandit / rebel / bounty_hunter / mercenary / all"), false);
        }
        return 1;
    }
}
