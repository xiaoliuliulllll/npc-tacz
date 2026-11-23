package com.npctacz.client;

import com.npctacz.entity.NpcFaction;
import com.npctacz.network.AdminSettingsMessage;
import com.npctacz.network.NpcNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局 NPC 管理员界面（Alt+N）。
 *
 * <p>所有数值都是玩家自己填数字，不再用滑块；三页：战斗/掉落、每阵营生成、世界与重置。
 * 服务端是唯一可信来源；非 OP 打开只读。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class NpcAdminScreen extends Screen {

    private static final NpcFaction[] FACTIONS = NpcFaction.values();
    private static final int PAGE_COUNT = 8;
    private static final String UI_BUILD = "UI9";
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static NpcAdminScreen instance;

    private AdminSettingsMessage snapshot;
    private boolean canEdit;
    private int page;
    private int scrollOffset;
    private static final int MAX_SCROLL = 120;
    private int panelX;
    private int panelY;
    private int panelW;
    private int contentX;
    private int contentW;
    private final List<Label> labels = new ArrayList<>();
    private final List<Hint> hints = new ArrayList<>();

    private EditBox damageBox;
    private EditBox fireBox;
    private EditBox difficultyBox;
    private EditBox ammoBox;
    private EditBox maxNpcBox;
    private EditBox allIntervalBox;
    private EditBox spawnOuterBox;
    private EditBox spawnInnerBox;
    private final List<EditBox> amountMinBoxes = new ArrayList<>();
    private final List<EditBox> amountMaxBoxes = new ArrayList<>();
    private final List<EditBox> intervalBoxes = new ArrayList<>();
    private final Map<EditBox, Runnable> autoApply = new LinkedHashMap<>();

    public NpcAdminScreen() {
        super(Component.literal("NPC 全局设置"));
        instance = this;
        LOGGER.info("[npc-tacz] admin UI build={} pages={}", UI_BUILD, PAGE_COUNT);
        NpcNetwork.requestAdminSettings();
    }

    public static void applySnapshot(AdminSettingsMessage message) {
        NpcAdminScreen screen = instance;
        if (screen == null || Minecraft.getInstance().screen != screen) {
            return;
        }
        screen.snapshot = message;
        screen.canEdit = message.canEdit;
        if (!(screen.getFocused() instanceof EditBox)) {
            screen.rebuild();
        }
    }

    @Override
    protected void init() {
        super.init();
        this.rebuild();
    }

    private void rebuild() {
        this.clearWidgets();
        this.labels.clear();
        this.hints.clear();
        this.amountMinBoxes.clear();
        this.amountMaxBoxes.clear();
        this.intervalBoxes.clear();
        this.autoApply.clear();
        this.damageBox = null;
        this.fireBox = null;
        this.difficultyBox = null;
        this.ammoBox = null;
        this.maxNpcBox = null;
        this.allIntervalBox = null;
        this.spawnOuterBox = null;
        this.spawnInnerBox = null;
        this.panelW = Math.max(240, Math.min(540, this.width - 8));
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = 4;
        this.contentX = this.panelX + 10;
        this.contentW = this.panelW - 20;
        if (this.snapshot == null) {
            this.addRenderableWidget(Button.builder(Component.literal("同步中…"), button -> {
            }).bounds(this.width / 2 - 60, this.height / 2, 120, 20).build()).active = false;
            return;
        }
        switch (this.page) {
            case 0 -> this.buildQuickPage();
            case 1 -> this.buildCombatPage();
            case 2 -> this.buildSpawnPage();
            case 3 -> this.buildWorldPage();
            case 4 -> this.buildFactionPage();
            case 5 -> this.buildSpreadRangePage();
            case 6 -> this.buildPerformancePage();
            default -> this.buildDamagePage();
        }
        // 固定页码按钮：无论滚动/翻页，页面总数和直接跳转始终可见。
        int tabGap = 2;
        int tabW = Math.max(16, (this.contentW - tabGap * (PAGE_COUNT - 1)) / PAGE_COUNT);
        int tabY = this.height - 56;
        for (int i = 0; i < PAGE_COUNT; i++) {
            final int target = i;
            this.addRenderableWidget(Button.builder(
                Component.literal((i == this.page ? "§e" : "§7") + (i + 1)),
                button -> {
                    this.page = target;
                    this.scrollOffset = 0;
                    this.rebuild();
                }
            ).bounds(this.contentX + i * (tabW + tabGap), tabY, tabW, 12).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("← 上一页"), button -> {
            this.page = (this.page + PAGE_COUNT - 1) % PAGE_COUNT;
            this.scrollOffset = 0;
            this.rebuild();
        }).bounds(this.contentX, this.height - 20, 62, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("下一页 →"), button -> {
            this.page = (this.page + 1) % PAGE_COUNT;
            this.scrollOffset = 0;
            this.rebuild();
        }).bounds(this.contentX + this.contentW - 62, this.height - 20, 62, 16).build());
        this.addRenderableWidget(Button.builder(
            Component.literal("聊天：" + (this.snapshot.chatNotifications ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("chat_notifications", 0,
                this.snapshot.chatNotifications ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX + this.contentW - 132, this.height - 20, 64, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("↑"), button -> {
            this.scrollOffset = Math.max(0, this.scrollOffset - 20);
            this.rebuild();
        }).bounds(this.contentX, this.height - 38, 18, 14).build()).active = this.scrollOffset > 0;
        this.addRenderableWidget(Button.builder(Component.literal("↓"), button -> {
            this.scrollOffset = Math.min(MAX_SCROLL, this.scrollOffset + 20);
            this.rebuild();
        }).bounds(this.contentX + 22, this.height - 38, 18, 14).build()).active = this.scrollOffset < MAX_SCROLL;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        this.runFocusedAuto();
        int next = this.scrollOffset - (int) Math.round(delta * 18.0D);
        next = Math.max(0, Math.min(MAX_SCROLL, next));
        if (next != this.scrollOffset) {
            this.scrollOffset = next;
            this.rebuild();
        }
        return true;
    }

    private void buildQuickPage() {
        int y = 30 - this.scrollOffset;
        this.drawHint(y, "常用开关：发光 / 死亡箱 / 战利品桶 / 玻璃透视 / 低配。");
        this.addRenderableWidget(Button.builder(Component.literal(
            "NPC发光：" + (this.snapshot.npcGlowEnabled ? "开" : "关")), button ->
            NpcNetwork.sendAdminUpdate("npc_glow", 0,
                this.snapshot.npcGlowEnabled ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX, y + 18, this.contentW, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal(
            "死亡箱：" + (this.snapshot.deathLootContainer ? "开" : "关")), button ->
            NpcNetwork.sendAdminUpdate("death_loot_container", 0,
                this.snapshot.deathLootContainer ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX, y + 40, this.contentW, 16).build()).active = this.canEdit;

        int barrelHalf = (this.contentW - 4) / 2;
        this.addRenderableWidget(Button.builder(Component.literal(
            "战利品桶自动消失：" + (this.snapshot.deathLootBarrelAutoRemove ? "开" : "关")), button ->
            NpcNetwork.sendAdminUpdate("death_loot_auto_remove", 0,
                this.snapshot.deathLootBarrelAutoRemove ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX, y + 62, barrelHalf, 16).build()).active = this.canEdit;
        this.drawLabel(this.contentX + barrelHalf + 4, y + 67, "消失分钟");
        EditBox barrelLifetimeBox = this.createNumberBox(
            this.contentX + barrelHalf + 52, y + 62, 42, 16,
            String.valueOf(Math.max(1, this.snapshot.deathLootBarrelLifetimeSeconds / 60)), false);
        this.addRenderableWidget(barrelLifetimeBox);
        this.registerAuto(barrelLifetimeBox, () -> {
            long minutes = Math.max(1L, parseLong(barrelLifetimeBox.getValue(), 20L));
            NpcNetwork.sendAdminUpdate("death_loot_lifetime", 0, minutes * 60.0D, 0.0D);
        });

        this.addRenderableWidget(Button.builder(Component.literal(
            "玻璃/树叶透视：" + (this.snapshot.visionOccludersEnabled ? "关" : "开")), button ->
            NpcNetwork.sendAdminUpdate("vision_occluders", 0,
                this.snapshot.visionOccludersEnabled ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX, y + 84, this.contentW, 16).build()).active = this.canEdit;
        int quickHalf = (this.contentW - 4) / 2;
        this.addRenderableWidget(Button.builder(Component.literal(
            "弹药进钻石盒：" + (this.snapshot.npcAmmoBoxDropEnabled ? "开" : "关")), button ->
            NpcNetwork.sendAdminUpdate("ammo_box_drop", 0,
                this.snapshot.npcAmmoBoxDropEnabled ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX, y + 106, quickHalf, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("低配预设：上限32"), button ->
            NpcNetwork.sendAdminUpdate("low_spec_preset", 0, 0.0D, 0.0D)
        ).bounds(this.contentX + quickHalf + 4, y + 106, quickHalf, 16).build()).active = this.canEdit;
        this.drawHint(y + 128, "钻石弹药盒默认关；开启后弹药盒掉落/进桶。手持盒左键双击或拖动可存入子弹。");
    }

    private void buildCombatPage() {
        int y = 32 - this.scrollOffset;
        this.numberRow(y, "全局伤害倍数", this.snapshot.damageMultiplier, "damage");
        this.numberRow(y + 18, "全局射速倍数", this.snapshot.fireRateMultiplier, "firerate");
        this.numberRow(y + 36, "反应时间倍数", this.snapshot.reactionTimeMultiplier, "reaction");
        this.numberRow(y + 54, "世界难度 (1-4)", this.snapshot.difficulty, "difficulty");
        int half = (this.contentW - 4) / 2;
        this.addRenderableWidget(Button.builder(
            Component.literal("枪械掉落：" + (this.snapshot.dropGuns ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("drop_guns", 0,
                this.snapshot.dropGuns ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX, y + 72, half, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(
            Component.literal("子弹掉落：" + (this.snapshot.dropAmmo ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("drop_ammo", 0,
                this.snapshot.dropAmmo ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX + half + 4, y + 72, half, 16).build()).active = this.canEdit;
        this.numberRow(y + 90, "掉落子弹倍率", this.snapshot.ammoDropMultiplier, "ammo_multiplier");
        this.addRenderableWidget(Button.builder(
            Component.literal("士兵敌对：" + (this.snapshot.soldierHostilePlayers ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("soldier_hostile", 0,
                this.snapshot.soldierHostilePlayers ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX, y + 108, half - 2, 16).build()).active = this.canEdit;
        this.drawLabel(this.contentX + half + 4, y + 113, "玩家爆头+");
        EditBox headshotBox = this.createNumberBox(this.contentX + half + 52, y + 108, 48, 16,
            String.format(java.util.Locale.ROOT, "%.1f", this.snapshot.playerHeadshotBonus), true);
        this.addRenderableWidget(headshotBox);
        this.registerAuto(headshotBox, () -> NpcNetwork.sendAdminUpdate(
            "headshot_bonus", 0, parseDouble(headshotBox.getValue(), 6.5D), 0.0D));

        int third = (this.contentW - 8) / 3;
        this.addRenderableWidget(Button.builder(Component.literal("节奏：慢"), button ->
            NpcNetwork.sendAdminUpdate("combat_cadence", 0, 0.0D, 0.0D)
        ).bounds(this.contentX, y + 128, third, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("标准"), button ->
            NpcNetwork.sendAdminUpdate("combat_cadence", 0, 1.0D, 0.0D)
        ).bounds(this.contentX + third + 4, y + 128, third, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("快"), button ->
            NpcNetwork.sendAdminUpdate("combat_cadence", 0, 2.0D, 0.0D)
        ).bounds(this.contentX + 2 * (third + 4), y + 128, third, 16).build()).active = this.canEdit;
        this.drawHint(y + 148, "数值改完按回车或点空白自动保存；按钮即时生效。");
    }

    private void buildSpawnPage() {
        int y = 32 - this.scrollOffset;
        int rowHeight = 18;
        this.drawHint(y - 10, "阵营名开关生成；min/max=数量，interval=秒；改完回车/点空白保存");
        for (int i = 0; i < FACTIONS.length; i++) {
            NpcFaction faction = FACTIONS[i];
            int rowY = y + i * rowHeight;
            boolean enabled = this.snapshot.spawnFactionEnabled.length > i
                && this.snapshot.spawnFactionEnabled[i];
            final int toggleIndex = i;
            this.addRenderableWidget(Button.builder(
                Component.literal((enabled ? "§a●" : "§c○") + factionLabel(faction)),
                button -> NpcNetwork.sendAdminUpdate("spawn_faction_enabled", toggleIndex,
                    enabled ? 0.0D : 1.0D, 0.0D)
            ).bounds(this.contentX, rowY, 44, 16).build()).active = this.canEdit;
            int boxX = this.contentX + 46;
            int boxW = Math.max(26, (this.contentW - 46 - 6) / 3);
            EditBox min = this.createNumberBox(boxX, rowY, boxW, 14,
                String.valueOf(this.snapshot.amountMin[i]), false);
            EditBox max = this.createNumberBox(boxX + boxW + 3, rowY, boxW, 14,
                String.valueOf(this.snapshot.amountMax[i]), false);
            EditBox interval = this.createNumberBox(boxX + (boxW + 3) * 2, rowY,
                boxW, 14, String.valueOf(this.snapshot.intervals[i] / 20L), false);
            this.addRenderableWidget(min);
            this.addRenderableWidget(max);
            this.addRenderableWidget(interval);
            this.amountMinBoxes.add(min);
            this.amountMaxBoxes.add(max);
            this.intervalBoxes.add(interval);
            final int index = i;
            Runnable applyRow = () -> this.applySpawnRow(index);
            this.registerAuto(min, applyRow);
            this.registerAuto(max, applyRow);
            this.registerAuto(interval, applyRow);
        }
        int allY = y + 96;
        long allSeconds = Math.max(1L, this.snapshot.allSpawnIntervalTicks / 20L);
        final long defaultAllSeconds = allSeconds;
        this.drawLabel(this.contentX, allY + 5, "全部间隔秒");
        this.allIntervalBox = this.createNumberBox(this.contentX + 76, allY, 60, 16,
            String.valueOf(defaultAllSeconds), false);
        this.addRenderableWidget(this.allIntervalBox);
        this.registerAuto(this.allIntervalBox, () -> {
            long seconds = Math.max(1L, parseLong(this.allIntervalBox.getValue(), defaultAllSeconds));
            NpcNetwork.sendAdminUpdate("spawn_interval_all", 0, seconds * 20.0D, 0.0D);
        });
        this.drawHint(allY + 20, "当前显示第1个阵营的间隔；修改后应用到全部阵营。");

        int rangeY = y + 122;
        double outer = this.snapshot.outerRadii.length > 0 ? this.snapshot.outerRadii[0] : 96.0D;
        double inner = this.snapshot.innerRadii.length > 0 ? this.snapshot.innerRadii[0] : 48.0D;
        this.drawLabel(this.contentX, rangeY + 5, "玩家外");
        this.spawnOuterBox = this.createNumberBox(this.contentX + 46, rangeY, 54, 16,
            String.valueOf(Math.round(outer)), false);
        this.addRenderableWidget(this.spawnOuterBox);
        this.drawLabel(this.contentX + 108, rangeY + 5, "玩家内");
        this.spawnInnerBox = this.createNumberBox(this.contentX + 154, rangeY, 54, 16,
            String.valueOf(Math.round(inner)), false);
        this.addRenderableWidget(this.spawnInnerBox);
        Runnable applyRange = () -> {
            long outerValue = Math.max(16L, Math.min(4096L,
                parseLong(this.spawnOuterBox.getValue(), 96L)));
            long innerValue = Math.max(16L, Math.min(outerValue,
                parseLong(this.spawnInnerBox.getValue(), 48L)));
            NpcNetwork.sendAdminUpdate("spawn_range_all", 0,
                (double) outerValue, (double) innerValue);
        };
        this.registerAuto(this.spawnOuterBox, applyRange);
        this.registerAuto(this.spawnInnerBox, applyRange);

        this.drawLabel(this.contentX, y + 151, "全服上限");
        this.maxNpcBox = this.createNumberBox(this.contentX + 64, y + 146, 64, 16,
            String.valueOf(this.snapshot.maxNpcs), false);
        this.addRenderableWidget(this.maxNpcBox);
        this.registerAuto(this.maxNpcBox, () -> NpcNetwork.sendAdminUpdate(
            "max_npcs", 0, parseLong(this.maxNpcBox.getValue(), this.snapshot.maxNpcs), 0.0D));
        this.drawHint(y + 166, "玩家外/玩家内 16-4096；改完回车/点空白自动保存。");
    }

    private void buildPerformancePage() {
        int y = 30 - this.scrollOffset;
        this.drawHint(y, "清理工具；所有按钮点击立即生效。");
        int fourth = (this.contentW - 12) / 4;
        this.addRenderableWidget(Button.builder(Component.literal("清理尸体"), button ->
            NpcNetwork.sendAdminUpdate("clean_corpses", 0, 0.0D, 0.0D)
        ).bounds(this.contentX, y + 20, fourth, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("清理掉落"), button ->
            NpcNetwork.sendAdminUpdate("clean_items", 0, 0.0D, 0.0D)
        ).bounds(this.contentX + fourth + 4, y + 20, fourth, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("补满弹药"), button ->
            NpcNetwork.sendAdminUpdate("refill_ammo", 0, 0.0D, 0.0D)
        ).bounds(this.contentX + 2 * (fourth + 4), y + 20, fourth, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("唤醒NPC"), button ->
            NpcNetwork.sendAdminUpdate("wake_npcs", 0, 0.0D, 0.0D)
        ).bounds(this.contentX + 3 * (fourth + 4), y + 20, fourth, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("清除附近NPC（64格）"), button ->
            NpcNetwork.sendAdminUpdate("clear_nearby_npcs", 0, 0.0D, 0.0D)
        ).bounds(this.contentX, y + 44, this.contentW, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("清除附近战利品桶（64格）"), button ->
            NpcNetwork.sendAdminUpdate("clear_loot_barrels", 0, 0.0D, 0.0D)
        ).bounds(this.contentX, y + 68, this.contentW, 16).build());
        this.drawHint(y + 92, "常用开关（发光/死亡箱/桶/透视/低配）在第1页。");
    }

    private void buildWorldPage() {
        int y = 22 - this.scrollOffset;
        this.numberRow(y, "全局移速倍数", this.snapshot.movementSpeedMultiplier, "move_speed");

        this.addRenderableWidget(Button.builder(
            Component.literal("NPC直接显示：" + (this.snapshot.npcCullEnabled ? "关" : "开")),
            button -> NpcNetwork.sendAdminUpdate("npc_cull", 0,
                this.snapshot.npcCullEnabled ? 0.0D : 1.0D,
                this.snapshot.npcCullEnabled ? this.snapshot.viewDistance : 96.0D)
        ).bounds(this.contentX, y + 22, this.contentW, 16).build()).active = this.canEdit;

        this.drawLabel(this.contentX, y + 48, "剔除距离");
        EditBox distanceBox = this.createNumberBox(this.contentX + 76, y + 43, 64, 16,
            String.valueOf(this.snapshot.viewDistance), false);
        this.addRenderableWidget(distanceBox);
        this.registerAuto(distanceBox, () -> {
            long distance = parseLong(distanceBox.getValue(), this.snapshot.viewDistance);
            NpcNetwork.sendAdminUpdate("npc_cull", 0,
                this.snapshot.npcCullEnabled ? 1.0D : 0.0D, distance);
        });

        int half = (this.contentW - 4) / 2;
        this.addRenderableWidget(Button.builder(
            Component.literal("自然生成：" + (this.snapshot.spawnEnabled ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("spawn_enabled", 0,
                this.snapshot.spawnEnabled ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX, y + 70, half, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(
            Component.literal("生成提示：" + (this.snapshot.generatePrompt ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("spawn_prompt", 0,
                this.snapshot.generatePrompt ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX + half + 4, y + 70, half, 16).build()).active = this.canEdit;

        this.addRenderableWidget(Button.builder(Component.literal("恢复默认"), button ->
            NpcNetwork.sendAdminUpdate("reset_defaults", 0, 0.0D, 0.0D)
        ).bounds(this.contentX, y + 94, this.contentW, 16).build()).active = this.canEdit;

        this.drawHint(y + 116, "直接显示开=不限制距离；剔除距离只在关闭直接显示时生效。");
        this.drawHint(y + 130, "NPC发光/死亡箱/战利品桶在第1页常用开关。");
    }

    private void buildFactionPage() {
        int y = 32 - this.scrollOffset;
        this.numberRow(y, "近战受伤倍数", this.snapshot.meleeDamageTakenMultiplier, "melee_taken");
        this.numberRow(y + 18, "所有阵营生命(all)", this.snapshot.healthMultiplier, "health");
        this.drawHint(y + 38, "下面单阵营：左=生命，右=伤害；改完回车/点空白保存。");
        for (int i = 0; i < FACTIONS.length; i++) {
            NpcFaction faction = FACTIONS[i];
            int rowY = y + 54 + i * 16;
            this.drawLabel(this.contentX, rowY + 5, factionLabel(faction));
            double health = this.snapshot.factionHealthMultipliers.length > i
                ? this.snapshot.factionHealthMultipliers[i] : 1.0D;
            double damage = this.snapshot.factionDamageMultipliers.length > i
                ? this.snapshot.factionDamageMultipliers[i] : 1.0D;
            EditBox healthBox = this.createNumberBox(this.contentX + 76, rowY, 48, 14,
                String.format(java.util.Locale.ROOT, "%.2f", health), true);
            EditBox damageBox = this.createNumberBox(this.contentX + 130, rowY, 48, 14,
                String.format(java.util.Locale.ROOT, "%.2f", damage), true);
            this.addRenderableWidget(healthBox);
            this.addRenderableWidget(damageBox);
            final int index = i;
            Runnable apply = () -> NpcNetwork.sendAdminUpdate("faction_stats", index,
                parseDouble(healthBox.getValue(), 1.0D),
                parseDouble(damageBox.getValue(), 1.0D));
            this.registerAuto(healthBox, apply);
            this.registerAuto(damageBox, apply);
        }
    }

    private void buildSpreadRangePage() {
        int y = 32 - this.scrollOffset;
        this.numberRow(y, "全局扩散系数", this.snapshot.spreadMultiplier, "spread_coeff");
        this.numberRow(y + 18, "全局射程系数", this.snapshot.rangeMultiplier, "range_all");
        this.drawHint(y + 38, "单阵营：左=扩散，右=射程；改完回车/点空白保存。");
        for (int i = 0; i < FACTIONS.length; i++) {
            NpcFaction faction = FACTIONS[i];
            int rowY = y + 54 + i * 16;
            this.drawLabel(this.contentX, rowY + 5, factionLabel(faction));
            double spread = this.snapshot.factionSpreadMultipliers.length > i
                ? this.snapshot.factionSpreadMultipliers[i] : 1.0D;
            double range = this.snapshot.factionRangeMultipliers.length > i
                ? this.snapshot.factionRangeMultipliers[i] : 1.0D;
            EditBox spreadBox = this.createNumberBox(this.contentX + 80, rowY, 40, 14,
                String.format(java.util.Locale.ROOT, "%.2f", spread), true);
            EditBox rangeBox = this.createNumberBox(this.contentX + 126, rowY, 40, 14,
                String.format(java.util.Locale.ROOT, "%.2f", range), true);
            this.addRenderableWidget(spreadBox);
            this.addRenderableWidget(rangeBox);
            final int index = i;
            Runnable apply = () -> NpcNetwork.sendAdminUpdate("faction_spread_range", index,
                parseDouble(spreadBox.getValue(), 1.0D),
                parseDouble(rangeBox.getValue(), 1.0D));
            this.registerAuto(spreadBox, apply);
            this.registerAuto(rangeBox, apply);
        }
    }

    private void buildDamagePage() {
        int y = 32 - this.scrollOffset;
        this.drawHint(y - 10, "四类伤害倍率，默认 1.0；改完回车/点空白自动保存。");
        this.numberRow(y + 8, "NPC→玩家伤害", this.snapshot.npcToPlayerDamageMultiplier,
            "npc_to_player_damage");
        this.numberRow(y + 28, "NPC→生物伤害", this.snapshot.npcToMobDamageMultiplier,
            "npc_to_mob_damage");
        this.numberRow(y + 48, "玩家→NPC伤害", this.snapshot.playerToNpcDamageMultiplier,
            "player_to_npc_damage");
        this.numberRow(y + 68, "生物→NPC伤害", this.snapshot.mobToNpcDamageMultiplier,
            "mob_to_npc_damage");
        this.drawHint(y + 94, "生物=非玩家的活体（包括 NPC、怪物、动物）。");
        this.drawHint(y + 108, "示例：玩家→NPC=0.5 表示玩家打 NPC 只造成一半伤害。");
        this.drawHint(y + 122, "NPC互打时，NPC→生物 与 生物→NPC 会同时生效（叠乘）。");
    }

    private void numberRow(int y, String label, double value, String action) {
        this.drawLabel(this.contentX, y + 5, label);
        EditBox box = this.createNumberBox(this.contentX + 100, y, 60, 16,
            String.format(java.util.Locale.ROOT, "%.2f", value), true);
        this.addRenderableWidget(box);
        this.registerAuto(box, () -> NpcNetwork.sendAdminUpdate(
            action, 0, parseDouble(box.getValue(), value), 0.0D));
        switch (action) {
            case "damage" -> this.damageBox = box;
            case "firerate" -> this.fireBox = box;
            case "reaction" -> { }
            case "difficulty" -> this.difficultyBox = box;
            case "ammo_multiplier" -> this.ammoBox = box;
            default -> {
            }
        }
    }

    private void registerAuto(EditBox box, Runnable apply) {
        this.autoApply.put(box, apply);
    }

    private void runFocusedAuto() {
        if (this.getFocused() instanceof EditBox box) {
            this.runAuto(box);
        }
    }

    private void runAuto(EditBox box) {
        Runnable apply = this.autoApply.get(box);
        if (apply != null) {
            apply.run();
        }
    }

    private boolean isInside(EditBox box, double mouseX, double mouseY) {
        return mouseX >= box.getX() && mouseX <= box.getX() + box.getWidth()
            && mouseY >= box.getY() && mouseY <= box.getY() + box.getHeight();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
            && this.getFocused() instanceof EditBox) {
            this.runFocusedAuto();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.runFocusedAuto();
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.getFocused() instanceof EditBox box && !this.isInside(box, mouseX, mouseY)) {
            this.runAuto(box);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        this.runFocusedAuto();
        super.onClose();
    }

    private EditBox createNumberBox(int x, int y, int width, int height, String value, boolean decimal) {
        EditBox box = new EditBox(this.font, x, y, width, height, Component.literal(value));
        box.setMaxLength(10);
        box.setValue(value);
        box.setEditable(this.canEdit);
        box.setFilter(text -> text.matches(decimal ? "[0-9.]*" : "[0-9]*"));
        return box;
    }

    private void applySpawnRow(int index) {
        if (index < 0 || index >= FACTIONS.length) {
            return;
        }
        int min = (int) parseLong(this.amountMinBoxes.get(index).getValue(), 1L);
        int max = (int) parseLong(this.amountMaxBoxes.get(index).getValue(), min);
        if (max < min) {
            max = min;
        }
        long seconds = parseLong(this.intervalBoxes.get(index).getValue(), 600L);
        NpcNetwork.sendAdminUpdate("spawn_amount", index, min, max);
        NpcNetwork.sendAdminUpdate("spawn_interval", index, seconds * 20.0D, 0.0D);
    }

    private static String factionLabel(NpcFaction faction) {
        return switch (faction) {
            case SOLDIER -> "士兵";
            case BANDIT -> "劫匪";
            case REBEL -> "叛军";
            case MERCENARY -> "雇佣兵";
            case BOUNTY_HUNTER -> "赏金猎人";
        };
    }

    private void drawLabel(int x, int y, String text) {
        this.labels.add(new Label(x, y, text));
    }

    private void drawHint(int y, String text) {
        this.hints.add(new Hint(y, text));
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Math.round(Double.parseDouble(value.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record Hint(int y, String text) {
    }

    private record Label(int x, int y, String text) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.fill(panelX, panelY, panelX + panelW, this.height - 8, 0xC0101018);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 16, 0xFF2A2A40);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, panelY + 4, 0xFFE8E8FF);
        if (this.snapshot == null) {
            graphics.drawCenteredString(this.font, Component.literal("正在从服务端同步…"),
                this.width / 2, this.height / 2 + 28, 0xFFFFCC66);
        } else {
            String state = this.canEdit ? "§aOP 可编辑" : "§c只读（需要 OP 权限 2）";
            graphics.drawString(this.font, state, panelX + 6, panelY + 16, 0xFFAAAAAA);
            if (this.width >= 320) {
                String npcInfo = "§7NPC " + this.snapshot.currentNpcs + "/" + this.snapshot.maxNpcs;
                graphics.drawString(this.font, npcInfo,
                    panelX + 92, panelY + 16, 0xFFAAAAAA);
            }
            if (this.canEdit && this.width >= 300) {
                String auto = "自动保存：回车/点空白";
                graphics.drawString(this.font, "§8" + auto,
                    panelX + panelW - this.font.width(auto) - 6, panelY + 16, 0xFF888888);
            }
            graphics.drawString(this.font, this.pageTitle() + "  §8" + UI_BUILD + " / 1.1.4",
                panelX + 6, panelY + 25, 0xFFFFFF66);
            for (Label label : this.labels) {
                graphics.drawString(this.font, "§f" + label.text, label.x, label.y, 0xFFFFFFFF);
            }
            for (Hint hint : this.hints) {
                graphics.drawString(this.font, "§7" + hint.text, contentX, hint.y, 0xFF999999);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String pageTitle() {
        return switch (this.page) {
            case 0 -> "第 1 页 / 8：常用开关";
            case 1 -> "第 2 页 / 8：战斗、掉落、攻击节奏";
            case 2 -> "第 3 页 / 8：生成数量、间隔、范围、上限";
            case 3 -> "第 4 页 / 8：世界、显示、恢复默认";
            case 4 -> "第 5 页 / 8：阵营生命/伤害、all、近战";
            case 5 -> "第 6 页 / 8：扩散 + 射程";
            case 6 -> "第 7 页 / 8：性能、清理";
            default -> "第 8 页 / 8：四类伤害倍率";
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (instance == this) {
            instance = null;
        }
        super.removed();
    }
}
