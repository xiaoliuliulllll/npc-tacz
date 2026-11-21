package com.npctacz.network;

import com.npctacz.data.NpcDifficultyData;
import com.npctacz.data.NpcSpawnConfigData;
import com.npctacz.entity.NpcFaction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：管理员界面完整快照（世界持久配置 + 当前玩家是否有编辑权限）。
 */
public final class AdminSettingsMessage {

    private static final NpcFaction[] FACTIONS = NpcFaction.values();

    public final int difficulty;
    public final boolean canEdit;
    public final double damageMultiplier;
    public final double fireRateMultiplier;
    public final double reactionTimeMultiplier;
    public final double healthMultiplier;
    public final double movementSpeedMultiplier;
    public final boolean soldierHostilePlayers;
    public final int viewDistance;
    public final double meleeDamageTakenMultiplier;
    public final double[] factionHealthMultipliers;
    public final double[] factionDamageMultipliers;
    public final boolean npcCullEnabled;
    public final int npcCullDistance;
    public final boolean chatNotifications;
    public final double playerHeadshotBonus;
    public final double spreadMultiplier;
    public final double rangeMultiplier;
    public final double[] factionRangeMultipliers;
    public final double[] factionSpreadMultipliers;
    public final boolean dropGuns;
    public final boolean dropAmmo;
    public final double ammoDropMultiplier;
    public final int maxNpcs;
    public final int currentNpcs;
    public final boolean spawnEnabled;
    public final boolean generatePrompt;
    public final boolean[] spawnFactionEnabled;
    public final long[] intervals;
    public final int[] amountMin;
    public final int[] amountMax;
    public final double[] innerRadii;
    public final double[] outerRadii;

    public AdminSettingsMessage(int difficulty, boolean canEdit,
                                double damageMultiplier, double fireRateMultiplier,
                                double reactionTimeMultiplier,
                                double healthMultiplier, double movementSpeedMultiplier,
                                boolean soldierHostilePlayers, int viewDistance,
                                double meleeDamageTakenMultiplier,
                                double[] factionHealthMultipliers, double[] factionDamageMultipliers,
                                boolean npcCullEnabled, int npcCullDistance,
                                boolean chatNotifications, double playerHeadshotBonus,
                                double spreadMultiplier, double rangeMultiplier,
                                double[] factionRangeMultipliers, double[] factionSpreadMultipliers,
                                boolean dropGuns, boolean dropAmmo, double ammoDropMultiplier,
                                int maxNpcs, int currentNpcs, boolean spawnEnabled, boolean generatePrompt,
                                boolean[] spawnFactionEnabled,
                                long[] intervals, int[] amountMin, int[] amountMax,
                                double[] innerRadii, double[] outerRadii) {
        this.difficulty = difficulty;
        this.canEdit = canEdit;
        this.damageMultiplier = damageMultiplier;
        this.fireRateMultiplier = fireRateMultiplier;
        this.reactionTimeMultiplier = reactionTimeMultiplier;
        this.healthMultiplier = healthMultiplier;
        this.movementSpeedMultiplier = movementSpeedMultiplier;
        this.soldierHostilePlayers = soldierHostilePlayers;
        this.viewDistance = viewDistance;
        this.meleeDamageTakenMultiplier = meleeDamageTakenMultiplier;
        this.factionHealthMultipliers = factionHealthMultipliers;
        this.factionDamageMultipliers = factionDamageMultipliers;
        this.npcCullEnabled = npcCullEnabled;
        this.npcCullDistance = npcCullDistance;
        this.chatNotifications = chatNotifications;
        this.playerHeadshotBonus = playerHeadshotBonus;
        this.spreadMultiplier = spreadMultiplier;
        this.rangeMultiplier = rangeMultiplier;
        this.factionRangeMultipliers = factionRangeMultipliers;
        this.factionSpreadMultipliers = factionSpreadMultipliers;
        this.dropGuns = dropGuns;
        this.dropAmmo = dropAmmo;
        this.ammoDropMultiplier = ammoDropMultiplier;
        this.maxNpcs = maxNpcs;
        this.currentNpcs = currentNpcs;
        this.spawnEnabled = spawnEnabled;
        this.generatePrompt = generatePrompt;
        this.spawnFactionEnabled = spawnFactionEnabled;
        this.intervals = intervals;
        this.amountMin = amountMin;
        this.amountMax = amountMax;
        this.innerRadii = innerRadii;
        this.outerRadii = outerRadii;
    }

    public static AdminSettingsMessage snapshot(ServerPlayer player) {
        NpcDifficultyData difficulty = NpcDifficultyData.get(player.serverLevel());
        NpcSpawnConfigData spawn = NpcSpawnConfigData.get(player.serverLevel());
        int n = FACTIONS.length;
        long[] intervals = new long[n];
        int[] mins = new int[n];
        int[] maxs = new int[n];
        boolean[] enabled = new boolean[n];
        double[] innerRadii = new double[n];
        double[] outerRadii = new double[n];
        double[] factionHealth = new double[n];
        double[] factionDamage = new double[n];
        double[] factionRange = new double[n];
        double[] factionSpread = new double[n];
        for (int i = 0; i < n; i++) {
            factionHealth[i] = difficulty.getFactionHealthMultiplier(FACTIONS[i]);
            factionDamage[i] = difficulty.getFactionDamageMultiplier(FACTIONS[i]);
            factionRange[i] = difficulty.getFactionRangeMultiplier(FACTIONS[i]);
            factionSpread[i] = difficulty.getFactionSpreadMultiplier(FACTIONS[i]);
            NpcSpawnConfigData.Rule rule = spawn.rule(FACTIONS[i]);
            intervals[i] = rule.intervalTicks;
            mins[i] = rule.amountMin;
            maxs[i] = rule.amountMax;
            enabled[i] = rule.enabled;
            innerRadii[i] = rule.innerRadius;
            outerRadii[i] = rule.outerRadius;
        }
        return new AdminSettingsMessage(
            difficulty.getDifficulty(),
            player.hasPermissions(2),
            difficulty.getGlobalDamageMultiplier(),
            difficulty.getGlobalFireRateMultiplier(),
            difficulty.getReactionTimeMultiplier(),
            difficulty.getGlobalHealthMultiplier(),
            difficulty.getGlobalMovementSpeedMultiplier(),
            difficulty.isSoldierHostilePlayers(),
            difficulty.getPlayerViewDistance(player.getUUID()),
            difficulty.getMeleeDamageTakenMultiplier(),
            factionHealth, factionDamage,
            difficulty.isPlayerCullEnabled(player.getUUID()),
            difficulty.getPlayerCullDistance(player.getUUID()),
            difficulty.areChatNotificationsEnabled(player.getUUID()),
            difficulty.getPlayerHeadshotBonus(),
            difficulty.getGlobalSpreadMultiplier(), difficulty.getGlobalRangeMultiplier(),
            factionRange, factionSpread,
            difficulty.isDropGuns(),
            difficulty.isDropAmmo(),
            difficulty.getAmmoDropMultiplier(),
            difficulty.getMaxNpcs(),
            com.npctacz.event.NpcSpawnEvents.countAllNpcs(player.getServer()),
            difficulty.isSpawnEnabled(),
            difficulty.isGeneratePrompt(),
            enabled, intervals, mins, maxs, innerRadii, outerRadii
        );
    }

    public static void encode(AdminSettingsMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.difficulty);
        buffer.writeBoolean(message.canEdit);
        buffer.writeDouble(message.damageMultiplier);
        buffer.writeDouble(message.fireRateMultiplier);
        buffer.writeDouble(message.reactionTimeMultiplier);
        buffer.writeDouble(message.healthMultiplier);
        buffer.writeDouble(message.movementSpeedMultiplier);
        buffer.writeBoolean(message.soldierHostilePlayers);
        buffer.writeVarInt(message.viewDistance);
        buffer.writeBoolean(message.npcCullEnabled);
        buffer.writeVarInt(message.npcCullDistance);
        buffer.writeBoolean(message.chatNotifications);
        buffer.writeDouble(message.playerHeadshotBonus);
        buffer.writeDouble(message.spreadMultiplier);
        buffer.writeDouble(message.rangeMultiplier);
        int n = NpcFaction.values().length;
        for (int i = 0; i < n; i++) {
            buffer.writeDouble(message.factionRangeMultipliers.length > i
                ? message.factionRangeMultipliers[i] : 1.0D);
            buffer.writeDouble(message.factionSpreadMultipliers.length > i
                ? message.factionSpreadMultipliers[i] : 1.0D);
        }
        buffer.writeDouble(message.meleeDamageTakenMultiplier);
        for (int i = 0; i < n; i++) {
            buffer.writeDouble(message.factionHealthMultipliers.length > i
                ? message.factionHealthMultipliers[i] : 1.0D);
            buffer.writeDouble(message.factionDamageMultipliers.length > i
                ? message.factionDamageMultipliers[i] : 1.0D);
        }
        buffer.writeBoolean(message.dropGuns);
        buffer.writeBoolean(message.dropAmmo);
        buffer.writeDouble(message.ammoDropMultiplier);
        buffer.writeVarInt(message.maxNpcs);
        buffer.writeVarInt(message.currentNpcs);
        buffer.writeBoolean(message.spawnEnabled);
        buffer.writeBoolean(message.generatePrompt);
        for (int i = 0; i < n; i++) {
            buffer.writeBoolean(message.spawnFactionEnabled.length > i && message.spawnFactionEnabled[i]);
        }
        for (int i = 0; i < n; i++) {
            buffer.writeLong(message.intervals.length > i ? message.intervals[i] : 0L);
            buffer.writeVarInt(message.amountMin.length > i ? message.amountMin[i] : 0);
            buffer.writeVarInt(message.amountMax.length > i ? message.amountMax[i] : 0);
            buffer.writeDouble(message.innerRadii.length > i ? message.innerRadii[i] : 48.0D);
            buffer.writeDouble(message.outerRadii.length > i ? message.outerRadii[i] : 96.0D);
        }
    }

    public static AdminSettingsMessage decode(FriendlyByteBuf buffer) {
        int difficulty = buffer.readVarInt();
        boolean canEdit = buffer.readBoolean();
        double damage = buffer.readDouble();
        double fire = buffer.readDouble();
        double reaction = buffer.readDouble();
        double healthMultiplier = buffer.readDouble();
        double movementSpeedMultiplier = buffer.readDouble();
        boolean soldierHostilePlayers = buffer.readBoolean();
        int viewDistance = buffer.readVarInt();
        boolean npcCullEnabled = buffer.readBoolean();
        int npcCullDistance = buffer.readVarInt();
        boolean chatNotifications = buffer.readBoolean();
        double playerHeadshotBonus = buffer.readDouble();
        double spreadMultiplier = buffer.readDouble();
        double rangeMultiplier = buffer.readDouble();
        int n = NpcFaction.values().length;
        double[] factionRangeMultipliers = new double[n];
        double[] factionSpreadMultipliers = new double[n];
        for (int i = 0; i < n; i++) {
            factionRangeMultipliers[i] = buffer.readDouble();
            factionSpreadMultipliers[i] = buffer.readDouble();
        }
        double meleeDamageTaken = buffer.readDouble();
        double[] factionHealthMultipliers = new double[n];
        double[] factionDamageMultipliers = new double[n];
        for (int i = 0; i < n; i++) {
            factionHealthMultipliers[i] = buffer.readDouble();
            factionDamageMultipliers[i] = buffer.readDouble();
        }
        boolean dropGuns = buffer.readBoolean();
        boolean dropAmmo = buffer.readBoolean();
        double ammoMultiplier = buffer.readDouble();
        int maxNpcs = buffer.readVarInt();
        int currentNpcs = buffer.readVarInt();
        boolean spawnEnabled = buffer.readBoolean();
        boolean generatePrompt = buffer.readBoolean();
        boolean[] spawnFactionEnabled = new boolean[n];
        for (int i = 0; i < n; i++) {
            spawnFactionEnabled[i] = buffer.readBoolean();
        }
        long[] intervals = new long[n];
        int[] mins = new int[n];
        int[] maxs = new int[n];
        boolean[] enabled = new boolean[n];
        double[] innerRadii = new double[n];
        double[] outerRadii = new double[n];
        for (int i = 0; i < n; i++) {
            intervals[i] = buffer.readLong();
            mins[i] = buffer.readVarInt();
            maxs[i] = buffer.readVarInt();
            innerRadii[i] = buffer.readDouble();
            outerRadii[i] = buffer.readDouble();
        }
        return new AdminSettingsMessage(difficulty, canEdit, damage, fire, reaction,
            healthMultiplier, movementSpeedMultiplier, soldierHostilePlayers, viewDistance,
            meleeDamageTaken, factionHealthMultipliers, factionDamageMultipliers,
            npcCullEnabled, npcCullDistance, chatNotifications, playerHeadshotBonus,
            spreadMultiplier, rangeMultiplier, factionRangeMultipliers, factionSpreadMultipliers,
            dropGuns, dropAmmo, ammoMultiplier, maxNpcs, currentNpcs,
            spawnEnabled, generatePrompt, spawnFactionEnabled, intervals, mins, maxs,
            innerRadii, outerRadii);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> com.npctacz.client.NpcAdminClientSettings.applySnapshot(this)));
        context.setPacketHandled(true);
    }
}
