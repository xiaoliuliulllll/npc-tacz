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

import java.util.ArrayList;
import java.util.List;

/**
 * 全局 NPC 管理员界面（Alt+N）。
 *
 * <p>所有数值都是玩家自己填数字，不再用滑块；三页：战斗/掉落、每阵营生成、世界与重置。
 * 服务端是唯一可信来源；非 OP 打开只读。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class NpcAdminScreen extends Screen {

    private static final NpcFaction[] FACTIONS = NpcFaction.values();
    private static NpcAdminScreen instance;

    private AdminSettingsMessage snapshot;
    private boolean canEdit;
    private int page;
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

    public NpcAdminScreen() {
        super(Component.literal("NPC 全局设置"));
        instance = this;
        NpcNetwork.requestAdminSettings();
    }

    public static void applySnapshot(AdminSettingsMessage message) {
        NpcAdminScreen screen = instance;
        if (screen == null || Minecraft.getInstance().screen != screen) {
            return;
        }
        screen.snapshot = message;
        screen.canEdit = message.canEdit;
        screen.rebuild();
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
            case 0 -> this.buildCombatPage();
            case 1 -> this.buildSpawnPage();
            case 2 -> this.buildWorldPage();
            case 3 -> this.buildFactionPage();
            default -> this.buildSpreadRangePage();
        }
        this.addRenderableWidget(Button.builder(Component.literal("← 上一页"), button -> {
            this.page = (this.page + 4) % 5;
            this.rebuild();
        }).bounds(this.contentX, this.height - 20, 62, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("下一页 →"), button -> {
            this.page = (this.page + 1) % 5;
            this.rebuild();
        }).bounds(this.contentX + this.contentW - 62, this.height - 20, 62, 16).build());
        this.addRenderableWidget(Button.builder(
            Component.literal("聊天：" + (this.snapshot.chatNotifications ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("chat_notifications", 0,
                this.snapshot.chatNotifications ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX + this.contentW - 132, this.height - 20, 64, 16).build());
    }

    private void buildCombatPage() {
        int y = 32;
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
        this.drawLabel(this.contentX + half + 4, y + 113, "爆头+");
        EditBox headshotBox = this.createNumberBox(this.contentX + half + 38, y + 108, 48, 16,
            String.format(java.util.Locale.ROOT, "%.1f", this.snapshot.playerHeadshotBonus), true);
        this.addRenderableWidget(headshotBox);
        this.addRenderableWidget(Button.builder(Component.literal("应用"), button -> {
            double value = parseDouble(headshotBox.getValue(), 6.5D);
            NpcNetwork.sendAdminUpdate("headshot_bonus", 0, value, 0.0D);
        }).bounds(this.contentX + half + 90, y + 108, half - 90, 16).build()).active = this.canEdit;
    }

    private void buildSpawnPage() {
        int y = 32;
        int rowHeight = 18;
        this.drawHint(y - 10, "点阵营名切换是否生成；min/max=数量，interval=秒");
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
            int boxW = Math.max(24, (this.contentW - 46 - 100) / 3);
            EditBox min = this.createNumberBox(boxX, rowY, boxW, 14,
                String.valueOf(this.snapshot.amountMin[i]), false);
            EditBox max = this.createNumberBox(boxX + boxW + 3, rowY, boxW, 14,
                String.valueOf(this.snapshot.amountMax[i]), false);
            EditBox interval = this.createNumberBox(boxX + (boxW + 3) * 2, rowY,
                boxW + 16, 14, String.valueOf(this.snapshot.intervals[i] / 20L), false);
            this.addRenderableWidget(min);
            this.addRenderableWidget(max);
            this.addRenderableWidget(interval);
            this.amountMinBoxes.add(min);
            this.amountMaxBoxes.add(max);
            this.intervalBoxes.add(interval);
            final int index = i;
            this.addRenderableWidget(Button.builder(Component.literal("应用"), button -> {
                this.applySpawnRow(index);
            }).bounds(this.contentX + this.contentW - 34, rowY, 34, 14).build()).active = this.canEdit;
        }
        int allY = y + 96;
        this.drawLabel(this.contentX, allY + 5, "全部间隔秒");
        this.allIntervalBox = this.createNumberBox(this.contentX + 76, allY, 54, 16, "600", false);
        this.addRenderableWidget(this.allIntervalBox);
        this.addRenderableWidget(Button.builder(Component.literal("应用到全部"), button -> {
            long seconds = parseLong(this.allIntervalBox.getValue(), 600L);
            NpcNetwork.sendAdminUpdate("spawn_interval_all", 0, seconds * 20.0D, 0.0D);
        }).bounds(this.contentX + 134, allY, 70, 16).build()).active = this.canEdit;
        this.drawHint(allY + 22, "阵营名点击切换是否自然生成");

        int rangeY = allY + 42;
        double outer = this.snapshot.outerRadii.length > 0 ? this.snapshot.outerRadii[0] : 96.0D;
        double inner = this.snapshot.innerRadii.length > 0 ? this.snapshot.innerRadii[0] : 48.0D;
        this.drawLabel(this.contentX, rangeY + 5, "外");
        this.spawnOuterBox = this.createNumberBox(this.contentX + 14, rangeY, 50, 16,
            String.valueOf(Math.round(outer)), false);
        this.addRenderableWidget(this.spawnOuterBox);
        this.drawLabel(this.contentX + 70, rangeY + 5, "内");
        this.spawnInnerBox = this.createNumberBox(this.contentX + 84, rangeY, 50, 16,
            String.valueOf(Math.round(inner)), false);
        this.addRenderableWidget(this.spawnInnerBox);
        this.addRenderableWidget(Button.builder(Component.literal("应用距离"), button -> {
            long outerValue = parseLong(this.spawnOuterBox.getValue(), 96L);
            long innerValue = parseLong(this.spawnInnerBox.getValue(), 48L);
            outerValue = Math.max(16L, Math.min(4096L, outerValue));
            innerValue = Math.max(16L, Math.min(outerValue, innerValue));
            NpcNetwork.sendAdminUpdate("spawn_range_all", 0,
                (double) outerValue, (double) innerValue);
        }).bounds(this.contentX + 140, rangeY, Math.max(56, this.contentW - 140), 16).build()).active = this.canEdit;
        this.drawHint(rangeY + 20, "玩家外/玩家内距离 16-4096，内不能大于外");
    }

    private void buildWorldPage() {
        int y = 22;
        this.numberRow(y, "全局生命倍数", this.snapshot.healthMultiplier, "health");
        this.numberRow(y + 18, "全局移速倍数", this.snapshot.movementSpeedMultiplier, "move_speed");

        this.addRenderableWidget(Button.builder(
            Component.literal("NPC直接显示：" + (this.snapshot.npcCullEnabled ? "关" : "开")),
            button -> NpcNetwork.sendAdminUpdate("npc_cull", 0,
                this.snapshot.npcCullEnabled ? 0.0D : 1.0D,
                this.snapshot.npcCullEnabled ? this.snapshot.viewDistance : 96.0D)
        ).bounds(this.contentX, y + 36, this.contentW, 16).build());

        this.drawLabel(this.contentX, y + 58, "剔除距离（关闭直接显示时）");
        EditBox distanceBox = this.createNumberBox(this.contentX + 124, y + 54, 58, 16,
            String.valueOf(this.snapshot.viewDistance), false);
        distanceBox.setEditable(true);
        this.addRenderableWidget(distanceBox);
        this.addRenderableWidget(Button.builder(Component.literal("应用"), button -> {
            long distance = parseLong(distanceBox.getValue(), this.snapshot.viewDistance);
            NpcNetwork.sendAdminUpdate("view_distance", 0, distance, 0.0D);
        }).bounds(this.contentX + 188, y + 54, 48, 16).build());

        this.drawLabel(this.contentX, y + 78, "全服 NPC 上限");
        this.maxNpcBox = this.createNumberBox(this.contentX + 100, y + 74, 60, 16,
            String.valueOf(this.snapshot.maxNpcs), false);
        this.addRenderableWidget(this.maxNpcBox);
        this.addRenderableWidget(Button.builder(Component.literal("应用"), button -> {
            long value = parseLong(this.maxNpcBox.getValue(), this.snapshot.maxNpcs);
            NpcNetwork.sendAdminUpdate("max_npcs", 0, value, 0.0D);
        }).bounds(this.contentX + 166, y + 74, 44, 16).build()).active = this.canEdit;

        int half = (this.contentW - 4) / 2;
        this.addRenderableWidget(Button.builder(
            Component.literal("自然生成：" + (this.snapshot.spawnEnabled ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("spawn_enabled", 0,
                this.snapshot.spawnEnabled ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX, y + 92, half, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(
            Component.literal("生成提示：" + (this.snapshot.generatePrompt ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("spawn_prompt", 0,
                this.snapshot.generatePrompt ? 0.0D : 1.0D, 0.0D)
        ).bounds(this.contentX + half + 4, y + 92, half, 16).build()).active = this.canEdit;

        int fifth = (this.contentW - 16) / 5;
        this.addRenderableWidget(Button.builder(Component.literal("清理尸体"), button ->
            NpcNetwork.sendAdminUpdate("clean_corpses", 0, 0.0D, 0.0D)
        ).bounds(this.contentX, y + 110, fifth, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("清理掉落"), button ->
            NpcNetwork.sendAdminUpdate("clean_items", 0, 0.0D, 0.0D)
        ).bounds(this.contentX + fifth + 4, y + 110, fifth, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("补满弹药"), button ->
            NpcNetwork.sendAdminUpdate("refill_ammo", 0, 0.0D, 0.0D)
        ).bounds(this.contentX + 2 * (fifth + 4), y + 110, fifth, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("唤醒NPC"), button ->
            NpcNetwork.sendAdminUpdate("wake_npcs", 0, 0.0D, 0.0D)
        ).bounds(this.contentX + 3 * (fifth + 4), y + 110, fifth, 16).build()).active = this.canEdit;
        this.addRenderableWidget(Button.builder(Component.literal("恢复默认"), button ->
            NpcNetwork.sendAdminUpdate("reset_defaults", 0, 0.0D, 0.0D)
        ).bounds(this.contentX + 4 * (fifth + 4), y + 110, fifth, 16).build()).active = this.canEdit;
        this.drawHint(y + 128, "直接显示开=不限制距离；关=默认 96 格外不显示/不攻击。");
    }

    private void buildFactionPage() {
        int y = 32;
        this.numberRow(y, "NPC受近战伤害倍数", this.snapshot.meleeDamageTakenMultiplier, "melee_taken");
        this.numberRow(y + 18, "所有阵营生命倍率(all)", this.snapshot.healthMultiplier, "health");
        this.drawHint(y + 36, "all 作用于所有阵营；下面单阵营：左=生命，右=伤害");
        for (int i = 0; i < FACTIONS.length; i++) {
            NpcFaction faction = FACTIONS[i];
            int rowY = y + 50 + i * 15;
            this.drawLabel(this.contentX, rowY + 5, factionLabel(faction));
            double health = this.snapshot.factionHealthMultipliers.length > i
                ? this.snapshot.factionHealthMultipliers[i] : 1.0D;
            double damage = this.snapshot.factionDamageMultipliers.length > i
                ? this.snapshot.factionDamageMultipliers[i] : 1.0D;
            EditBox healthBox = this.createNumberBox(this.contentX + 76, rowY, 44, 14,
                String.format(java.util.Locale.ROOT, "%.2f", health), true);
            EditBox damageBox = this.createNumberBox(this.contentX + 124, rowY, 44, 14,
                String.format(java.util.Locale.ROOT, "%.2f", damage), true);
            this.addRenderableWidget(healthBox);
            this.addRenderableWidget(damageBox);
            final int index = i;
            this.addRenderableWidget(Button.builder(Component.literal("应用"), button -> {
                double h = parseDouble(healthBox.getValue(), 1.0D);
                double d = parseDouble(damageBox.getValue(), 1.0D);
                NpcNetwork.sendAdminUpdate("faction_stats", index, h, d);
            }).bounds(this.contentX + 172, rowY, 44, 14).build()).active = this.canEdit;
        }
    }

    private void buildVisibilityPage() {
        int y = 32;
        this.addRenderableWidget(Button.builder(
            Component.literal("NPC剔除（不显示）：" + (this.snapshot.npcCullEnabled ? "开" : "关")),
            button -> NpcNetwork.sendAdminUpdate("npc_cull", 0,
                this.snapshot.npcCullEnabled ? 0.0D : 1.0D,
                this.snapshot.npcCullDistance)
        ).bounds(this.contentX, y, this.contentW, 18).build());

        this.drawLabel(this.contentX, y + 32, "NPC剔除距离 (32-1024)");
        EditBox cullBox = this.createNumberBox(this.contentX + 124, y + 28, 58, 16,
            String.valueOf(this.snapshot.npcCullDistance), false);
        cullBox.setEditable(true);
        this.addRenderableWidget(cullBox);
        this.addRenderableWidget(Button.builder(Component.literal("应用"), button -> {
            long distance = parseLong(cullBox.getValue(), this.snapshot.npcCullDistance);
            NpcNetwork.sendAdminUpdate("npc_cull", 0,
                this.snapshot.npcCullEnabled ? 1.0D : 0.0D, distance);
        }).bounds(this.contentX + 188, y + 28, 48, 16).build());

        this.drawHint(y + 56, "开启后：超过剔除距离的 NPC 不显示，也不会攻击你。");
        this.drawHint(y + 70, "实际可见/受击距离 = min(个人可见距离, 剔除距离)。");
    }

    private void buildSpreadRangePage() {
        int y = 32;
        this.numberRow(y, "全局扩散系数", this.snapshot.spreadMultiplier, "spread_coeff");
        this.numberRow(y + 18, "全局射程系数(all)", this.snapshot.rangeMultiplier, "range_all");
        this.drawHint(y + 38, "单阵营：左=扩散系数，右=射程系数（默认 1.0）");
        for (int i = 0; i < FACTIONS.length; i++) {
            NpcFaction faction = FACTIONS[i];
            int rowY = y + 50 + i * 15;
            this.drawLabel(this.contentX, rowY + 5, factionLabel(faction));
            double spread = this.snapshot.factionSpreadMultipliers.length > i
                ? this.snapshot.factionSpreadMultipliers[i] : 1.0D;
            double range = this.snapshot.factionRangeMultipliers.length > i
                ? this.snapshot.factionRangeMultipliers[i] : 1.0D;
            EditBox spreadBox = this.createNumberBox(this.contentX + 80, rowY, 40, 14,
                String.format(java.util.Locale.ROOT, "%.2f", spread), true);
            EditBox rangeBox = this.createNumberBox(this.contentX + 122, rowY, 40, 14,
                String.format(java.util.Locale.ROOT, "%.2f", range), true);
            this.addRenderableWidget(spreadBox);
            this.addRenderableWidget(rangeBox);
            final int index = i;
            this.addRenderableWidget(Button.builder(Component.literal("应用"), button -> {
                double s = parseDouble(spreadBox.getValue(), 1.0D);
                double r = parseDouble(rangeBox.getValue(), 1.0D);
                NpcNetwork.sendAdminUpdate("faction_spread_range", index, s, r);
            }).bounds(this.contentX + 164, rowY, 44, 14).build()).active = this.canEdit;
        }
    }

    private void numberRow(int y, String label, double value, String action) {
        this.drawLabel(this.contentX, y + 5, label);
        EditBox box = this.createNumberBox(this.contentX + 100, y, 60, 16,
            String.format(java.util.Locale.ROOT, "%.2f", value), true);
        this.addRenderableWidget(box);
        this.addRenderableWidget(Button.builder(Component.literal("应用"), button -> {
            double parsed = parseDouble(box.getValue(), value);
            NpcNetwork.sendAdminUpdate(action, 0, parsed, 0.0D);
        }).bounds(this.contentX + 164, y, 44, 16).build()).active = this.canEdit;
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
            graphics.drawString(this.font, this.pageTitle(), panelX + 6, panelY + 25, 0xFFFFFF66);
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
            case 0 -> "第 1 页 / 5：战斗、掉落";
            case 1 -> "第 2 页 / 5：每个阵营生成（填数字）";
            case 2 -> "第 3 页 / 5：世界、NPC直接显示、生命/移速、工具";
            case 3 -> "第 4 页 / 5：阵营血量/伤害、all生命、近战受伤";
            default -> "第 5 页 / 5：扩散+射程（全局/单阵营）";
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
