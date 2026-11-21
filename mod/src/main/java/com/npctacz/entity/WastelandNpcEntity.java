package com.npctacz.entity;

import com.npctacz.config.NpcConfig;
import com.npctacz.data.NpcDifficultyData;
import com.npctacz.entity.ai.NpcAmmoState;
import com.npctacz.entity.ai.NpcGunRules;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.entity.sync.ModSyncedEntityData;
import com.npctacz.entity.ai.NpcNavigator;
import com.npctacz.entity.goal.InvestigateSoundGoal;
import com.npctacz.entity.goal.NpcDoorBreakGoal;
import com.npctacz.entity.goal.NpcMeleeCombatGoal;
import com.npctacz.entity.goal.NpcPatrolGoal;
import com.npctacz.entity.goal.NpcRangedCombatGoal;
import com.npctacz.entity.goal.NpcTargetGoal;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.resource.index.CommonAttachmentIndex;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 荒原 NPC 基类。
 *
 * <p>职责划分（重构后）：</p>
 * <ul>
 *   <li>{@link NpcAmmoState}：弹药/武器状态机（远程 ↔ 近战、补弹、掉落弹匣）；</li>
 *   <li>{@link NpcNavigator}：寻路辅助（卡住绕行、跳跃、上梯子、落水回岸）；</li>
 *   <li>{@code com.npctacz.entity.goal}：行为 Goal（索敌、远程交火、近战、破门、巡逻、循声）；</li>
 *   <li>本类：数据、派系敌对、装备、掉落、存档、动画同步和对外 API。</li>
 * </ul>
 *
 * <p>所有代码均为本项目原创；TACZ 只作为运行时依赖通过公开 API 调用，
 * 不包含任何第三方模组的代码或资源。</p>
 */
public abstract class WastelandNpcEntity extends PathfinderMob {

    public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final EntityDataAccessor<String> DATA_FACTION =
        SynchedEntityData.defineId(WastelandNpcEntity.class, EntityDataSerializers.STRING);
    /** 开火动画同步标记（客户端播 attack）。 */
    private static final EntityDataAccessor<Boolean> DATA_FIRING =
        SynchedEntityData.defineId(WastelandNpcEntity.class, EntityDataSerializers.BOOLEAN);
    /** 换弹动画同步标记（客户端播 reload）。 */
    private static final EntityDataAccessor<Boolean> DATA_RELOADING =
        SynchedEntityData.defineId(WastelandNpcEntity.class, EntityDataSerializers.BOOLEAN);
    /** 瞄准同步标记（客户端选 gun_hold 动画，并给手臂加抬枪姿态）。 */
    private static final EntityDataAccessor<Boolean> DATA_AIMING =
        SynchedEntityData.defineId(WastelandNpcEntity.class, EntityDataSerializers.BOOLEAN);
    /** 击杀玩家后的嘲讽动作（快速蹲起）同步标记。 */
    private static final EntityDataAccessor<Boolean> DATA_TAUNTING =
        SynchedEntityData.defineId(WastelandNpcEntity.class, EntityDataSerializers.BOOLEAN);
    /** 该 NPC 绑定的模型 id（空串 = 未绑定）；只存通用模型数据，具体由模型附属解释。 */
    private static final EntityDataAccessor<String> DATA_MODEL_ID =
        SynchedEntityData.defineId(WastelandNpcEntity.class, EntityDataSerializers.STRING);

    /** 离最近可用玩家超过这个距离就切「弱运行」。 */
    private static double weakRunDistance() {
        return Math.max(16.0D, com.npctacz.config.NpcGlobalConfig.getInt("weak_run_distance", 128));
    }

    /** 兜底枪表：枪库筛选失败时也能拿到一把能开的枪。 */
    private static final String[] KNOWN_GUN_IDS = {
        "tacz:ak47", "tacz:m4a1", "tacz:hk_mp5a5", "tacz:glock_17",
        "tacz:type_81", "tacz:kar98", "tacz:m249", "tacz:uzi"
    };

    /** 当前手持枪的 id（子类的装备逻辑会写它）。 */
    @Nullable protected ResourceLocation gunId;
    @Nullable protected ResourceLocation ammoId;

    private final NpcAmmoState ammoState = new NpcAmmoState(this);
    private final NpcNavigator navigator = new NpcNavigator(this);

    private int firingVisualTicks;
    private int reloadVisualTicks;
    private int equipAttempts;
    private boolean shootWarned;
    private boolean equipWarned;

    private boolean weakRunning;
    private int tauntTicks;
    private BlockPos homePos = BlockPos.ZERO;
    @Nullable private BlockPos soundInterestPos;
    private long soundInterestUntilTick;
    private boolean lootDropped;
    /** 9 格拾取背包：每格最多 64 个，死亡 100% 掉落。 */
    public static final int PICKUP_SLOTS = 9;
    public static final int PICKUP_STACK_SIZE = 64;
    private final NonNullList<ItemStack> pickupInventory =
        NonNullList.withSize(PICKUP_SLOTS, ItemStack.EMPTY);
    private int clientReloadTicks;
    /** 被谁先打：优先 100% 反击先攻击自己的单位（最多锁定 400 tick）。 */
    @Nullable
    private LivingEntity retaliationTarget;
    private int retaliationTicks;
    /** 是否有“单个 NPC 显式指定”的模型（优先于阵营/全体默认）。 */
    private boolean modelExplicitOverride;
    /** 死亡后已经过的 tick，用于尸体清理。 */
    private int corpseTicks;
    /** 上一次向同阵营发出警报的 tick，避免每次受击全图拉人。 */
    private int lastAllyAlertTick = -1000;
    /** 受击硬直/踉跄，短暂降低精度并停止移动。 */
    private int flinchTicks;
    private int idleLookCooldown;
    private int lastCalloutTick = -1000;
    /** 切换新目标后的转身硬直结束 tick。 */
    private int turnReadyTick;
    /** 当前目标连续失去视线的 tick 数；超过阈值就丢目标，防止隔墙锁人。 */
    private int targetLosLostTicks;
    private int cachedNpcDifficulty = 2;
    private long cachedDifficultyReadTick = Long.MIN_VALUE;
    private double cachedRangeMultiplier = 1.0D;
    private long cachedRangeReadTick = Long.MIN_VALUE;
    /** 原版僵尸基础移速；所有 NPC 的基准速度 = 该值 × npc_speed_multiplier。 */
    private static final double ZOMBIE_BASE_SPEED = 0.23D;
    private float baseMaxHealth = 20.0F;
    private float appliedHealthMultiplier = -1.0F;
    private float appliedSpeedMultiplier = -1.0F;

    protected WastelandNpcEntity(EntityType<? extends WastelandNpcEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.xpReward = 8;
        var baseHealthAttribute = this.getAttribute(
            net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (baseHealthAttribute != null) {
            this.baseMaxHealth = (float) baseHealthAttribute.getBaseValue();
        }
        if (this.getNavigation() instanceof GroundPathNavigation navigation) {
            navigation.setCanOpenDoors(true);
            navigation.setCanPassDoors(true);
            navigation.setCanFloat(true);
        }
    }

    public abstract NpcFaction getFaction();

    // ------------------------------------------------------------------ 行为注册

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new NpcDoorBreakGoal(this));
        this.goalSelector.addGoal(2, new NpcRangedCombatGoal(this));
        this.goalSelector.addGoal(3, new NpcMeleeCombatGoal(this));
        this.goalSelector.addGoal(4, new InvestigateSoundGoal(this));
        this.goalSelector.addGoal(5, new NpcPatrolGoal(this));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(0, new NpcTargetGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_FACTION, getFaction().name());
        this.entityData.define(DATA_FIRING, false);
        this.entityData.define(DATA_RELOADING, false);
        this.entityData.define(DATA_AIMING, false);
        this.entityData.define(DATA_TAUNTING, false);
        this.entityData.define(DATA_MODEL_ID, "");
    }

    
    /**
     * 把 NPC 的动画状态喂给 TACZ 的第三人称动画系统。
     *
     * <p>TACZ 的 {@code HumanoidModelMixin}/{@code PlayerModelMixin} 会对**任何手持枪械的
     * LivingEntity** 调用 {@code InnerThirdPersonManager.setRotationAnglesHead}，从中读取
     * {@link ModSyncedEntityData} 的 AIMING_PROGRESS / RELOAD_STATE 等键来播放它自己的
     * 持枪、瞄准、换弹动作。玩家由 TACZ 自己维护这些键，NPC 需要在这里同步。</p>
     *
     * <p>所以皮肤模型（原版人形渲染器）的士兵/劫匪/叛军会自动获得 TACZ 的第三人称
     * 手臂动作，不需要复制 TACZ 的任何动画资源。</p>
     */
    /** 客户端动画同步节流：状态变化/每 10 tick 才写一次 TACZ 同步键。 */
    private boolean lastSyncedAiming;
    private boolean lastSyncedFiring;
    private boolean lastSyncedReloading;

    private void syncTaczClientAnimationState() {
        boolean aimingNow = this.isAiming();
        boolean firingNow = this.isFiringAnimation();
        boolean reloadingNow = this.isReloadingAnimation();
        boolean changed = aimingNow != this.lastSyncedAiming
            || firingNow != this.lastSyncedFiring
            || reloadingNow != this.lastSyncedReloading;
        if (!changed && this.tickCount % 10 != 0) {
            return;
        }
        this.lastSyncedAiming = aimingNow;
        this.lastSyncedFiring = firingNow;
        this.lastSyncedReloading = reloadingNow;
        try {
            boolean aiming = aimingNow;
            ModSyncedEntityData.AIMING_PROGRESS_KEY.setValue(this, aiming ? 1.0F : 0.0F);
            ModSyncedEntityData.IS_AIMING_KEY.setValue(this, aiming);
            ModSyncedEntityData.SHOOT_COOL_DOWN_KEY.setValue(this, this.isFiringAnimation() ? 0L : 100L);
            ModSyncedEntityData.DRAW_COOL_DOWN_KEY.setValue(this, 0L);
            ModSyncedEntityData.IS_BOLTING_KEY.setValue(this, false);
            ModSyncedEntityData.SPRINT_TIME_KEY.setValue(this, 0.0F);
            ReloadState reloadState = new ReloadState();
            if (this.isReloadingAnimation()) {
                this.clientReloadTicks++;
                reloadState.setStateType(ReloadState.StateType.EMPTY_RELOAD_FEEDING);
                reloadState.setCountDown(Math.max(0L, 40L - this.clientReloadTicks));
            } else {
                this.clientReloadTicks = 0;
                reloadState.setStateType(ReloadState.StateType.NOT_RELOADING);
                reloadState.setCountDown(0L);
            }
            ModSyncedEntityData.RELOAD_STATE_KEY.setValue(this, reloadState);
        } catch (Throwable ignored) {
            // TACZ 版本差异或动画联动失败时保持原版动作，绝不能影响游戏
        }
    }

    // ------------------------------------------------------------------ 生成

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType reason, @Nullable SpawnGroupData spawnData,
                                        @Nullable CompoundTag dataTag) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        // 防止存档/刷怪蛋把 NPC 写成无敌；玩家和子弹必须永远能打中
        this.setInvulnerable(false);
        this.invulnerableTime = 0;
        this.enforceMovementSpeed();
        this.refreshAdminAttributes(true);
        this.equipRandomGun();
        this.equipRandomArmor();
        if (!this.hasGunInHand()) {
            this.equipKnownGun();
        }
        this.ammoState.onGunEquipped();
        // 追击/寻路范围：默认 32 对远程 AI 太短，按交战范围提升到最多 64
        var followRange = this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(Math.max(
                followRange.getBaseValue(),
                Math.min(64.0D, getEngageRange())
            ));
        }
        this.getPersistentData().putInt("NpcTaczVersion", 4);
        this.homePos = this.blockPosition();
        this.applyIdentity();
        return data;
    }

    // ------------------------------------------------------------------ 每 tick

    @Override
    public void tick() {
        super.tick();
        // 尸体清理：死亡后立刻停止 AI/寻路，到时限强制移除（原版 20 tick，可配置）
        if (!this.level().isClientSide && (this.isDeadOrDying() || this.getHealth() <= 0.0F)) {
            this.corpseTicks++;
            this.setDeltaMovement(Vec3.ZERO);
            this.getNavigation().stop();
            int lifetime = Math.max(1, com.npctacz.config.NpcGlobalConfig.getInt("corpse_lifetime_ticks", 20));
            if (this.corpseTicks >= lifetime) {
                this.discard();
            }
            return;
        }
        if (this.isMovementLocked()) {
            // 强制停留：清掉所有寻路移动（右键雇佣兵切换）
            this.getNavigation().stop();
            this.navigator.stop();
        }
        this.tickRetaliation();
        if (this.tauntTicks > 0) {
            this.tickTaunt();
            return;
        }
        if (!this.level().isClientSide && this.level().getDifficulty() == Difficulty.PEACEFUL) {
            this.discard();
            return;
        }
        if (this.level().isClientSide) {
            this.syncTaczClientAnimationState();
            return;
        }
        this.ammoState.tick();
        this.navigator.tick();
        this.tickAnimationState();
        this.tickEquipmentFallback();
        this.tickWeakRun();
        this.tickSoundInterest();
        this.tickDebugTrace();
        this.tickFallbackAi();
        this.tickHumanBehavior();
        this.tickItemPickup();
    }

    private void tickEquipmentFallback() {
        if (this.tickCount <= 5 || this.tickCount % 100 != 0) {
            return;
        }
        // 旧存档/枪包更新后：强行删掉爆炸枪，重新挑一把非爆炸枪
        if (this.gunId != null && (NpcGunRules.isExplosiveId(this.gunId)
            || NpcGunRules.isExplosiveGun(this.getGunIndex()))) {
            this.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                net.minecraft.world.item.ItemStack.EMPTY);
            this.gunId = null;
        }
        boolean needGun = this.isRangedState()
            ? (this.gunId == null || !this.hasGunInHand())
            : (this.ammoState.getDropGun().isEmpty() && this.gunId == null);
        if (needGun) {
            this.equipAttempts++;
            this.equipRandomGun();
            if (this.gunId == null || !this.hasGunInHand()) {
                this.equipKnownGun();
            }
            if (this.hasGunInHand()) {
                // 近战状态被迫补枪的情况：直接切回远程，不要拿着枪继续挥拳
                this.ammoState.onGunEquipped();
            }
            if (this.equipAttempts <= 2) {
                LOGGER.info("[npc-tacz] equip attempt {}: gunId={} hand={} mag={} reserve={}",
                    this.equipAttempts, this.gunId, this.getMainHandItem().getItem(),
                    this.ammoState.magazine(), this.ammoState.reserve());
            }
        }
        if (this.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
            this.equipRandomArmor();
        }
        this.refreshAdminAttributes(false);
        ItemStack hand = this.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(hand);
        if (gun != null) {
            // 旧存档修复：只有确实缺配件时才扫描附件表，且 600 tick 最多一次
            if (this.tickCount % 600 == 0 && this.hasMissingAttachment(hand, gun)) {
                this.installRandomAttachments(hand);
            }
            this.sanitizeExplosiveAttachments();
        }
    }

    /** 每 10 tick 拾取周围 8 格内的掉落物（100% 概率），每格最多 64 个。 */
    private void tickItemPickup() {
        if (!this.canPickUpItems()) {
            return;
        }
        // 1 秒一次，并按实体 id 错开，避免几十个 NPC 同一 tick 扫实体
        if (this.level().isClientSide || (this.tickCount + this.getId()) % 20 != 0) {
            return;
        }
        if (this.isPickupInventoryFull()) {
            return;
        }
        try {
            int minDelay = Math.max(20,
                com.npctacz.config.NpcGlobalConfig.getInt("pickup_delay_min", 40));
            int maxDelay = Math.max(minDelay,
                com.npctacz.config.NpcGlobalConfig.getInt("pickup_delay_max", 60));
            java.util.List<ItemEntity> items = this.level().getEntitiesOfClass(
                ItemEntity.class, this.getBoundingBox().inflate(8.0D),
                item -> item.isAlive() && !item.hasPickUpDelay() && !item.getItem().isEmpty());
            for (ItemEntity itemEntity : items) {
                ItemStack stack = itemEntity.getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                // 新掉落物在地上躺 2~3 秒再捡；同一物品的随机延迟固定，避免每 tick 抖动
                int delayRange = maxDelay - minDelay + 1;
                int delay = minDelay + Math.floorMod(itemEntity.getUUID().hashCode(), delayRange);
                if (itemEntity.tickCount < delay) {
                    continue;
                }
                int inserted = this.insertIntoPickupInventory(stack);
                if (inserted <= 0) {
                    continue;
                }
                if (inserted >= stack.getCount()) {
                    itemEntity.discard();
                } else {
                    itemEntity.setItem(stack.copyWithCount(stack.getCount() - inserted));
                }
            }
        } catch (Throwable ignored) {
            // 拾取失败不能影响 AI
        }
    }

    /** 雇佣兵等特殊实体可以关闭自动拾取。 */
    protected boolean canPickUpItems() {
        return true;
    }

    private boolean isPickupInventoryFull() {
        for (ItemStack stack : this.pickupInventory) {
            if (stack.isEmpty() || stack.getCount() < PICKUP_STACK_SIZE) {
                return false;
            }
        }
        return true;
    }

    private int insertIntoPickupInventory(ItemStack source) {
        int remaining = source.getCount();
        int max = PICKUP_STACK_SIZE;
        // 先叠到已有同类物品
        for (int i = 0; i < PICKUP_SLOTS && remaining > 0; i++) {
            ItemStack slot = this.pickupInventory.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, source)) {
                continue;
            }
            int space = max - slot.getCount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            slot.grow(moved);
            remaining -= moved;
        }
        // 再放空槽，每槽最多 64
        for (int i = 0; i < PICKUP_SLOTS && remaining > 0; i++) {
            if (!this.pickupInventory.get(i).isEmpty()) {
                continue;
            }
            int moved = Math.min(max, remaining);
            ItemStack copy = source.copyWithCount(moved);
            this.pickupInventory.set(i, copy);
            remaining -= moved;
        }
        return source.getCount() - remaining;
    }

    /** 死亡时 100% 掉落拾取背包。 */
    private void dropPickupInventory() {
        for (int i = 0; i < PICKUP_SLOTS; i++) {
            ItemStack stack = this.pickupInventory.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack drop = stack.copy();
            if (com.tacz.guns.api.item.IGun.getIGunOrNull(drop) != null) {
                NpcGunRules.applyBestFireMode(drop);
            }
            this.spawnAtLocation(drop);
            this.pickupInventory.set(i, ItemStack.EMPTY);
        }
    }

    public int getPickupItemCount() {
        int count = 0;
        for (ItemStack stack : this.pickupInventory) {
            count += stack.getCount();
        }
        return count;
    }

    private void tickWeakRun() {
        if (this.tickCount % 20 != 0) {
            return;
        }
        Player nearest = this.level().getNearestPlayer(this, weakRunDistance());
        boolean weak = nearest == null || nearest.isSpectator();
        if (weak != this.weakRunning) {
            this.weakRunning = weak;
            if (weak) {
                if (this.getTarget() instanceof Player) {
                    this.setTarget(null);
                }
                this.clearSoundInterest();
            }
        }
    }

    /**
     * 兼容兜底 AI：某些整合包的 AI 模组会接管/抑制自定义实体的 goalSelector，
     * 表现为 NPC 不移动、不索敌、不开火。这里只在“战斗/巡逻 goal 都没有运行”时
     * 用最低限度的目标扫描 + 直追 + 开火兜底，正常游戏不会重复执行。
     */
    private static int fallbackAiLogs;
    private int fallbackScanCooldown;

    private void tickFallbackAi() {
        if (this.level().isClientSide) {
            return;
        }
        if (!com.npctacz.config.NpcGlobalConfig.getBoolean("fallback_ai", true)) {
            return;
        }
        if (this.tickCount % 20 != 0) {
            return;
        }
        // 战斗/巡逻 goal 正常运行时不再重复执行，避免双倍寻路/射击拖帧
        boolean combatGoalRunning = this.goalSelector.getRunningGoals().anyMatch(wrapped -> {
            var goal = wrapped.getGoal();
            return goal instanceof com.npctacz.entity.goal.NpcRangedCombatGoal
                || goal instanceof com.npctacz.entity.goal.NpcMeleeCombatGoal
                || goal instanceof com.npctacz.entity.goal.NpcPatrolGoal;
        });
        if (combatGoalRunning) {
            return;
        }
        // 其它 AI 模组可能把实体置为 NoAI；兜底时恢复（嘲讽期间除外）
        if (this.isNoAi() && this.tauntTicks <= 0) {
            this.setNoAi(false);
            if (fallbackAiLogs < 6) {
                fallbackAiLogs++;
                LOGGER.info("[npc-tacz] fallback AI reset NoAI: faction={}", getFaction());
            }
        }
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive() || !this.isHostileTo(target)) {
            target = com.npctacz.entity.ai.NpcCombatHelper.scanForTarget(this, 48.0D);
            if (target == null) {
                Player nearest = this.level().getNearestPlayer(this, 48.0D);
                if (nearest != null && this.isHostileTo(nearest)) {
                    target = nearest;
                }
            }
            if (target != null) {
                this.setTarget(target);
            }
        }
        if (target == null || !target.isAlive() || !this.isHostileTo(target)) {
            return;
        }
        this.getLookControl().setLookAt(target, 30.0F, 30.0F);
        double distanceSqr = this.distanceToSqr(target);
        double distance = Math.sqrt(distanceSqr);
        // 不依赖 goalSelector：直接驱动一次移动/开火
        if (distanceSqr > 6.25D) {
            this.navigator().moveTo(target, 1.05D);
            if (fallbackAiLogs < 10) {
                fallbackAiLogs++;
                LOGGER.info("[npc-tacz] fallback move: faction={} target={} dist={}",
                    getFaction(), target.getName().getString(), String.format("%.1f", distance));
            }
        } else if (this.hasGunInHand() && this.isRangedState() && this.canShoot()) {
            this.shootAt(target, 0.0F);
            if (fallbackAiLogs < 10) {
                fallbackAiLogs++;
                LOGGER.info("[npc-tacz] fallback shoot: faction={} target={} dist={}",
                    getFaction(), target.getName().getString(), String.format("%.1f", distance));
            }
        }
    }

    /** 调试用：开启 config 的 debug.traceEnabled 后每 5 秒打一条状态日志（仅玩家附近，避免刷屏）。 */
    private void tickDebugTrace() {
        boolean trace = com.npctacz.config.NpcGlobalConfig.getBoolean(
            "trace_enabled", NpcConfig.VALUES.traceEnabled.get());
        if (!trace || this.tickCount % 100 != 0) {
            return;
        }
        // 只打印玩家 48 格内的 NPC，避免几十上百只 NPC 把日志刷爆
        if (this.level().getNearestPlayer(this, 48.0D) == null) {
            return;
        }
        LivingEntity target = this.getTarget();
        LOGGER.info("[npc-tacz] trace: faction={} state={} mag={} reserve={} gun={} hand={} target={} dist={} pos={} weak={} stuck={}",
            getFaction(), this.ammoState.state(), this.ammoState.magazine(), this.ammoState.reserve(),
            this.gunId, this.getMainHandItem().getItem(),
            target == null ? "none" : target.getName().getString(),
            target == null ? "-" : String.format("%.1f", Math.sqrt(this.distanceToSqr(target))),
            this.blockPosition(), this.weakRunning, this.navigator.isStuck());
    }

    private void tickSoundInterest() {
        if (this.soundInterestPos != null && this.level().getGameTime() > this.soundInterestUntilTick) {
            this.soundInterestPos = null;
        }
    }

    private void tickAnimationState() {
        if (this.firingVisualTicks > 0) {
            this.firingVisualTicks--;
            if (this.firingVisualTicks <= 0) {
                this.entityData.set(DATA_FIRING, false);
            }
        }
        if (this.reloadVisualTicks > 0) {
            this.reloadVisualTicks--;
            if (this.reloadVisualTicks <= 0) {
                this.entityData.set(DATA_RELOADING, false);
            }
        }
    }

    // ------------------------------------------------------------------ 击杀玩家嘲讽

    public boolean isTaunting() {
        return this.entityData.get(DATA_TAUNTING);
    }

    /** Called on the server when this NPC kills a player. Duration is 3-6 seconds. */
    public void startTaunt() {
        if (this.level().isClientSide) {
            return;
        }
        this.tauntTicks = 60 + this.random.nextInt(61);
        this.entityData.set(DATA_TAUNTING, true);
        this.setNoAi(true);
        this.setTarget(null);
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        this.setPose(net.minecraft.world.entity.Pose.CROUCHING);
        this.setShiftKeyDown(true);
    }

    private void tickTaunt() {
        if (this.level().isClientSide) {
            return;
        }
        if (--this.tauntTicks <= 0) {
            this.stopTaunt();
            return;
        }
        // Fast crouch spam: toggle every 3 ticks (~6.7 times per second).
        if (this.tauntTicks % 3 == 0) {
            boolean crouch = !this.isCrouching();
            this.setPose(crouch
                ? net.minecraft.world.entity.Pose.CROUCHING
                : net.minecraft.world.entity.Pose.STANDING);
            this.setShiftKeyDown(crouch);
        }
    }

    private void stopTaunt() {
        this.tauntTicks = 0;
        this.entityData.set(DATA_TAUNTING, false);
        this.setNoAi(false);
        this.setPose(net.minecraft.world.entity.Pose.STANDING);
        this.setShiftKeyDown(false);
    }

    // ------------------------------------------------------------------ 动画同步

    public boolean isFiringAnimation() {
        return this.entityData.get(DATA_FIRING);
    }

    public boolean isReloadingAnimation() {
        return this.entityData.get(DATA_RELOADING);
    }

    /** 客户端读取：这一帧是否处于瞄准状态（用于 gun_hold 动画和抬枪姿态）。 */
    public boolean isAiming() {
        return this.entityData.get(DATA_AIMING);
    }

    public void setAiming(boolean aiming) {
        if (this.level().isClientSide) {
            return;
        }
        if (this.entityData.get(DATA_AIMING) != aiming) {
            this.entityData.set(DATA_AIMING, aiming);
        }
    }

    public void startFiringAnimation() {
        if (this.level().isClientSide) {
            return;
        }
        this.entityData.set(DATA_FIRING, true);
        this.firingVisualTicks = 14;
        // 皮肤模型（原版人形渲染器）没有自定义开火动画，这里用挥臂表现射击
        this.swing(InteractionHand.MAIN_HAND);
    }

    public void startReloadAnimation() {
        if (this.level().isClientSide) {
            return;
        }
        this.entityData.set(DATA_RELOADING, true);
        this.reloadVisualTicks = 40;
        // 换弹时挥一下手臂，皮肤模型也有可读的换弹动作
        this.swing(InteractionHand.MAIN_HAND);
    }

    /** 切换武器瞬间：停寻路、清水平速度，避免原地抽搐。 */
    public void afterWeaponSwitch() {
        this.getNavigation().stop();
        this.navigator.requestRepath();
        Vec3 motion = this.getDeltaMovement();
        this.setDeltaMovement(0.0D, motion.y, 0.0D);
    }

    // ------------------------------------------------------------------ 战斗 API

    public NpcAmmoState ammo() {
        return this.ammoState;
    }

    public NpcNavigator navigator() {
        return this.navigator;
    }

    public boolean isRangedState() {
        return this.ammoState.isRanged();
    }

    /** 是否正在换弹（换弹期间不能射击）。 */
    public boolean isReloadingState() {
        return this.ammoState.isReloading();
    }

    public boolean isMeleeState() {
        return this.ammoState.isMelee();
    }

    public NpcAmmoState.State getWeaponState() {
        return this.ammoState.state();
    }

    public int getMagazineAmmo() {
        return this.ammoState.magazine();
    }

    /** 备弹（兼容旧 API / 调试命令）。 */
    public int getAmmo() {
        return this.ammoState.reserve();
    }

    /** 设置备弹（兼容旧代码 / addon）。 */
    public void setAmmo(int amount) {
        this.ammoState.setReserve(amount);
    }

    public boolean hasGunInHand() {
        return this.ammoState.hasGunInHand();
    }

    public boolean canShoot() {
        return this.ammoState.canShoot();
    }

    public void shootAt(LivingEntity target) {
        this.ammoState.shootAt(target, 0.0F);
    }

    public void shootAt(LivingEntity target, float extraSpread) {
        this.ammoState.shootAt(target, extraSpread);
    }

    // ------------------------------------------------------------------ 派系 / 目标

    /** 是否允许把创造模式玩家当目标（全局配置）。 */
    protected boolean allowsCreativePlayers() {
        return com.npctacz.config.NpcGlobalConfig.getBoolean("target_creative_players", false);
    }

    /** 是否允许把旁观模式玩家当目标（全局配置）。 */
    protected boolean allowsSpectatorPlayers() {
        return com.npctacz.config.NpcGlobalConfig.getBoolean("target_spectator_players", false);
    }

    /** 玩家是否发出足够大的声音（冲刺/游泳/摔落），允许绕过正面视野被发现。 */
    public boolean isLoudTarget(LivingEntity target) {
        if (!(target instanceof Player player) || player.isSpectator() || player.isShiftKeyDown()) {
            return false;
        }
        if (this.distanceToSqr(player) > 24.0D * 24.0D) {
            return false;
        }
        return player.isSprinting() || player.isSwimming() || player.fallDistance > 2.5F;
    }

    /** 枪械/爆炸命中属于巨响，背身也会被惊动。 */
    public static boolean isLoudAttack(net.minecraft.world.damagesource.DamageSource source) {
        if (source == null) {
            return false;
        }
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
            return true;
        }
        var direct = source.getDirectEntity();
        if (direct == null) {
            return false;
        }
        String id = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
            .getKey(direct.getType()));
        return "tacz:bullet".equals(id) || "tacz:kinetic_bullet".equals(id);
    }

    /** 目标是否在正面 150° 视场内（半角 75°），用于发现新目标/背身。 */
    public boolean isTargetInFrontArc(LivingEntity target, float halfAngleDegrees) {
        if (target == null) {
            return false;
        }
        net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(
            target.getX() - this.getX(), 0.0D, target.getZ() - this.getZ());
        if (to.lengthSqr() < 1.0E-6D) {
            return true;
        }
        // 用头部朝向而不是身体 yaw：NPC 的 LookControl 只转头，身体不会立即跟转。
        float yaw = this.getYHeadRot();
        double forwardX = -Math.sin(Math.toRadians(yaw));
        double forwardZ = Math.cos(Math.toRadians(yaw));
        double dot = (forwardX * to.x + forwardZ * to.z) / to.length();
        double cos = Math.cos(Math.toRadians(Math.max(1.0F, Math.min(179.0F, halfAngleDegrees))));
        return dot >= cos;
    }

    /** 是否已经面向目标（用于“先转视角再攻击”）。 */
    public boolean isFacingTarget(LivingEntity target, float toleranceDegrees) {
        if (target == null) {
            return true;
        }
        net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(
            target.getX() - this.getX(), 0.0D, target.getZ() - this.getZ());
        if (to.lengthSqr() < 1.0E-6D) {
            return true;
        }
        float yaw = this.getYHeadRot();
        double forwardX = -Math.sin(Math.toRadians(yaw));
        double forwardZ = Math.cos(Math.toRadians(yaw));
        double dot = (forwardX * to.x + forwardZ * to.z) / to.length();
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
        return angle <= Math.max(1.0F, toleranceDegrees);
    }

    @Override
    public boolean doHurtTarget(net.minecraft.world.entity.Entity target) {
        if (target instanceof LivingEntity living
            && (!this.isTurnReady() || !this.isFacingTarget(living, 12.0F))) {
            this.getLookControl().setLookAt(living, 30.0F, 30.0F);
            return false;
        }
        return super.doHurtTarget(target);
    }

    public boolean isHostileTo(LivingEntity other) {
        if (other == this || !other.isAlive()) {
            return false;
        }
        if (other instanceof Player player) {
            // 只敌对非创造/非旁观玩家（可用全局配置放开）；弱运行状态不主动接战
            boolean attackCreative = com.npctacz.config.NpcGlobalConfig.getBoolean("target_creative_players", false);
            boolean attackSpectator = com.npctacz.config.NpcGlobalConfig.getBoolean("target_spectator_players", false);
            if (!attackCreative && player.isCreative()) {
                return false;
            }
            if (!attackSpectator && player.isSpectator()) {
                return false;
            }
            if (!this.isWithinPlayerViewDistance(player)) {
                return false;
            }
            return !this.weakRunning;
        }
        if (other instanceof WastelandNpcEntity npc) {
            return getFaction().isHostileTo(npc.getFaction());
        }
        if (other instanceof Enemy) {
            return com.npctacz.config.NpcGlobalConfig.getBoolean("attack_hostile_mobs", false);
        }
        return false;
    }

    /** 记录当前目标是否还能看见；目标隔墙超过 20 tick 会由 NpcTargetGoal 丢弃。 */
    public void noteTargetVisibility(boolean visible) {
        if (visible) {
            this.targetLosLostTicks = 0;
        } else {
            this.targetLosLostTicks++;
        }
    }

    public int getTargetLosLostTicks() {
        return this.targetLosLostTicks;
    }

    /** 玩家自己的 NPC 可见/受击距离；超过后 NPC 不把该玩家当目标。 */
    public boolean isWithinPlayerViewDistance(LivingEntity target) {
        if (!(target instanceof Player player)
            || !(this.level() instanceof ServerLevel serverLevel)) {
            return true;
        }
        var data = com.npctacz.data.NpcDifficultyData.get(serverLevel);
        if (!data.isPlayerCullEnabled(player.getUUID())) {
            return true;
        }
        int distance = data.getPlayerViewDistance(player.getUUID());
        return this.distanceToSqr(player) <= (double) distance * distance;
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null && target != this.getTarget()
            && !this.isHostileTo(target) && target != this.retaliationTarget) {
            return;
        }
        if (target != null && target != this.getTarget()) {
            if (!this.isTargetInFrontArc(target, 75.0F)
                && !this.isLoudTarget(target)
                && target != this.retaliationTarget) {
                return;
            }
            // 新目标必须真实可见，不能从墙后/队友共享目标里隔墙锁定。
            if (!com.npctacz.entity.ai.NpcCombatHelper.canSee(this, target)) {
                return;
            }
        }
        if (target instanceof Player player) {
            if ((!allowsCreativePlayers() && player.isCreative())
                || (!allowsSpectatorPlayers() && player.isSpectator())) {
                return;
            }
            if (!this.isWithinPlayerViewDistance(player)) {
                return;
            }
        }
        // 反击锁定期间，其他目标不能把先手敌人挤掉（100% 先打攻击自己的单位）
        LivingEntity forced = this.getForcedTarget();
        if (forced != null && target != forced) {
            return;
        }
        // 永不攻击同阵营/友军 NPC
        if (target instanceof WastelandNpcEntity other && other != this
            && !this.isHostileTo(other)) {
            return;
        }
        LivingEntity previousTarget = this.getTarget();
        super.setTarget(target);
        if (target != null && target != previousTarget) {
            int min = Math.max(0, com.npctacz.config.NpcGlobalConfig.getInt("npc_turn_delay_min", 8));
            int max = Math.max(min, com.npctacz.config.NpcGlobalConfig.getInt("npc_turn_delay_max", 16));
            this.turnReadyTick = this.tickCount + min + this.getRandom().nextInt(max - min + 1);
        } else if (target == null) {
            this.turnReadyTick = 0;
        }
    }

    /** 先手反击目标（有效时优先级最高）。 */
    @Nullable
    public LivingEntity getForcedTarget() {
        if (this.retaliationTicks > 0 && this.retaliationTarget != null
            && this.retaliationTarget.isAlive()
            && this.isHostileTo(this.retaliationTarget)) {
            return this.retaliationTarget;
        }
        return null;
    }

    private void tickRetaliation() {
        if (this.retaliationTicks > 0) {
            this.retaliationTicks--;
        }
        if (this.retaliationTarget != null
            && (!this.retaliationTarget.isAlive()
                || this.retaliationTicks <= 0
                || !this.isHostileTo(this.retaliationTarget))) {
            this.retaliationTarget = null;
            this.retaliationTicks = 0;
        }
    }

    /** 调试用：前若干次受伤把来源/结算打进日志，定位“打不到 NPC”用。 */
    private static int hurtDebugLogs;
    /** 累计受伤次数，供枪械伤害兜底判断“伤害是否真的到达了实体”。 */
    private volatile long hurtEventCounter;

    public long hurtEventCounter() {
        return this.hurtEventCounter;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        // 玩家近战/枪械/爆炸永远可以伤害 NPC（防止 isInvulnerable 存档标记卡住）
        if (source.getEntity() instanceof Player || source.getDirectEntity() instanceof Player) {
            return false;
        }
        return super.isInvulnerableTo(source);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        this.hurtEventCounter++;
        boolean hurt = super.hurt(source, amount);
        if (!this.level().isClientSide && hurtDebugLogs < 8) {
            hurtDebugLogs++;
            LOGGER.info("[npc-tacz] npc hurt: victim={} source={} direct={} amount={} result={} health={}",
                this.getName().getString(),
                source.getEntity() == null ? "null" : source.getEntity().getName().getString(),
                source.getDirectEntity() == null ? "null"
                    : source.getDirectEntity().getType().toString(),
                amount, hurt, this.getHealth());
        }
        if (hurt && !this.level().isClientSide) {
            this.flinchTicks = Math.max(this.flinchTicks, 6 + this.getRandom().nextInt(6));
            if (com.npctacz.config.NpcGlobalConfig.getBoolean("npc_voice", true)) {
                this.level().playSound(null, this,
                    net.minecraft.sounds.SoundEvents.PLAYER_HURT,
                    net.minecraft.sounds.SoundSource.HOSTILE, 0.45F,
                    0.85F + this.getRandom().nextFloat() * 0.3F);
            }
            Entity attackerEntity = source.getEntity();
            if (attackerEntity instanceof LivingEntity attacker
                && attacker != this
                && this.isHostileTo(attacker)) {
                // 先手敌人已经锁定时不换目标；目标死了/超时后新的攻击者才会接管
                boolean keepFirst = this.retaliationTarget != null
                    && this.retaliationTarget.isAlive()
                    && this.retaliationTarget != attacker
                    && this.retaliationTicks > 0;
                boolean noticed = this.isTargetInFrontArc(attacker, 75.0F)
                    || this.isLoudTarget(attacker) || isLoudAttack(source);
                if (!keepFirst && noticed) {
                    this.retaliationTarget = attacker;
                    this.retaliationTicks = 400;
                    this.setTarget(attacker);
                }
                if (noticed) {
                    this.alertAllies(attacker);
                }
            }
        }
        return hurt;
    }

    /** 强制停留（雇佣兵"停留"模式）：本地图内禁止一切寻路移动。 */
    public boolean isMovementLocked() {
        return false;
    }

    /** 是否会被原版敌对怪主动攻击；赏金猎人全中立。 */
    public boolean acceptsMobAggression() {
        return getFaction() != NpcFaction.BOUNTY_HUNTER;
    }

    // ------------------------------------------------------------------ 难度与距离

    public int getNpcDifficulty() {
        if (this.level() instanceof ServerLevel serverLevel) {
            long now = serverLevel.getGameTime();
            if (cachedDifficultyReadTick == Long.MIN_VALUE || now < cachedDifficultyReadTick
                || now - cachedDifficultyReadTick > 20L) {
                cachedNpcDifficulty = NpcDifficultyData.get(serverLevel).getDifficulty();
                cachedDifficultyReadTick = now;
            }
            return cachedNpcDifficulty;
        }
        return 2;
    }

    /** 全局/阵营射程系数。 */
    public double rangeMultiplier() {
        if (this.level() instanceof ServerLevel serverLevel) {
            long now = serverLevel.getGameTime();
            if (cachedRangeReadTick == Long.MIN_VALUE || now < cachedRangeReadTick
                || now - cachedRangeReadTick > 20L) {
                var data = com.npctacz.data.NpcDifficultyData.get(serverLevel);
                cachedRangeMultiplier = data.getGlobalRangeMultiplier()
                    * data.getFactionRangeMultiplier(this.getFaction());
                cachedRangeReadTick = now;
            }
            return cachedRangeMultiplier;
        }
        return 1.0D;
    }

    protected double rangeAdjusted(double base) {
        return base * this.rangeMultiplier();
    }

    /** 最大有效射程（子类按枪械类型覆盖）。 */
    public double getAttackRange() {
        return this.rangeAdjusted(32.0D);
    }

    /** 主动索敌半径。 */
    public double getTargetScanRange() {
        return this.rangeAdjusted(28.0D);
    }

    /** 感知范围：玩家在这个范围内会被主动追击。 */
    public double getAwarenessRange() {
        return Math.max(this.rangeAdjusted(96.0D), getTargetScanRange());
    }

    /** 理想交火距离：射程的 0.8，限制在 4~28 格。 */
    public double getPreferredRange() {
        return Math.max(4.0D, Math.min(getAttackRange() * 0.8D, 28.0D));
    }

    /** 交战/追击范围：射程的 1.25 倍，且不小于感知范围。 */
    public double getEngageRange() {
        return Math.max(getAttackRange() * 1.25D, Math.max(getTargetScanRange(), getAwarenessRange()));
    }

    // ------------------------------------------------------------------ 弹药数值（子类覆盖）

    public int getInitialAmmo() {
        return 90 + this.random.nextInt(31);
    }

    public int getMaxAmmo() {
        return getInitialAmmo();
    }

    public int getRefillAmount() {
        return getFaction() == NpcFaction.REBEL ? 30 + this.random.nextInt(16) : 30;
    }

    public int getNpcRpm(IGun gun, ItemStack stack) {
        return gun.getRPM(stack);
    }

    public float getNpcSpreadDegrees() {
        return 0.0F;
    }

    // ------------------------------------------------------------------ 装备

    public void equipRandomGun() {
        try {
            Set<Map.Entry<ResourceLocation, CommonGunIndex>> all = TimelessAPI.getAllCommonGunIndex();
            List<Map.Entry<ResourceLocation, CommonGunIndex>> pool = new ArrayList<>();
            String wanted = chooseCategory();
            for (Map.Entry<ResourceLocation, CommonGunIndex> entry : all) {
                CommonGunIndex index = entry.getValue();
                String type = index == null ? null : index.getType();
                if (type == null || index.getGunData() == null || index.getBulletData() == null) {
                    continue;
                }
                if (NpcGunRules.isExplosiveId(entry.getKey())
                    || NpcGunRules.isExplosiveGun(index)) {
                    continue;
                }
                if (wanted == null ? isAllowedType(type) : type.equalsIgnoreCase(wanted)) {
                    pool.add(entry);
                }
            }
            if (pool.isEmpty()) {
                for (Map.Entry<ResourceLocation, CommonGunIndex> entry : all) {
                    CommonGunIndex index = entry.getValue();
                    if (index == null || index.getGunData() == null || index.getBulletData() == null) {
                        continue;
                    }
                    if (NpcGunRules.isExplosiveId(entry.getKey())
                        || NpcGunRules.isExplosiveGun(index)) {
                        continue;
                    }
                    if (isAllowedType(index.getType())) {
                        pool.add(entry);
                    }
                }
            }
            if (pool.isEmpty()) {
                return;
            }
            Map.Entry<ResourceLocation, CommonGunIndex> selected =
                pool.get(this.random.nextInt(pool.size()));
            this.gunId = selected.getKey();
            this.ammoId = selected.getValue().getGunData().getAmmoId();
            ItemStack stack = createGunStack(this.gunId);
            if (stack.isEmpty()) {
                return;
            }
            installRandomAttachments(stack);
            this.setItemInHand(InteractionHand.MAIN_HAND, stack);
        } catch (Throwable throwable) {
            this.onGunEquipError(throwable);
        }
    }

    /** 兜底装备：即使枪库筛选失败也保证有枪。 */
    public boolean equipKnownGun() {
        for (String id : KNOWN_GUN_IDS) {
            ResourceLocation location = new ResourceLocation(id);
            if (NpcGunRules.isExplosiveId(location)) {
                continue;
            }
            if (TimelessAPI.getCommonGunIndex(location).isEmpty()) {
                continue;
            }
            ItemStack stack = createGunStack(location);
            if (stack.isEmpty()) {
                continue;
            }
            CommonGunIndex index = TimelessAPI.getCommonGunIndex(location).orElse(null);
            this.gunId = location;
            if (index != null && index.getGunData() != null) {
                this.ammoId = index.getGunData().getAmmoId();
            }
            installRandomAttachments(stack);
            this.setItemInHand(InteractionHand.MAIN_HAND, stack);
            this.sanitizeExplosiveAttachments();
            LOGGER.info("[npc-tacz] equipped fallback gun {}", location);
            return true;
        }
        LOGGER.warn("[npc-tacz] fallback gun equip failed (gunIndex={})",
            TimelessAPI.getAllCommonGunIndex().size());
        return false;
    }

    public void onGunEquipError(Throwable throwable) {
        if (!this.equipWarned) {
            this.equipWarned = true;
            LOGGER.error("[npc-tacz] gun equip failed", throwable);
        }
    }

    public ItemStack createGunStack(ResourceLocation id) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("tacz", "modern_kinetic_gun"));
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return ItemStack.EMPTY;
        }
        gun.setGunId(stack, id);
        return stack;
    }

    /** 供 /tacznpc attach 用：补齐手上枪缺失配件，返回补上的配件数。 */
    public int repairGunAttachments() {
        ItemStack hand = this.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(hand);
        if (gun == null) {
            return 0;
        }
        int before = 0;
        int after = 0;
        try {
            for (AttachmentType type : AttachmentType.values()) {
                if (type == AttachmentType.NONE
                    || com.tacz.guns.api.DefaultAssets.isEmptyAttachmentId(
                        gun.getAttachmentId(hand, type))) {
                    continue;
                }
                before++;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            this.sanitizeExplosiveAttachments();
            this.installRandomAttachments(hand);
            for (AttachmentType type : AttachmentType.values()) {
                if (type == AttachmentType.NONE
                    || com.tacz.guns.api.DefaultAssets.isEmptyAttachmentId(
                        gun.getAttachmentId(hand, type))) {
                    continue;
                }
                after++;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return Math.max(0, after - before);
    }

    /** 统计这把枪允许但还空着的配件槽数量。 */
    public int missingAttachmentCount(ItemStack gunStack, IGun gun) {
        int missing = 0;
        try {
            for (AttachmentType type : AttachmentType.values()) {
                if (type == AttachmentType.NONE || !gun.allowAttachmentType(gunStack, type)) {
                    continue;
                }
                if (com.tacz.guns.api.DefaultAssets.isEmptyAttachmentId(
                    gun.getAttachmentId(gunStack, type))) {
                    missing++;
                }
            }
        } catch (Throwable ignored) {
            return 0;
        }
        return missing;
    }

    /** 快速判断：允许的配件槽里有没有空槽。 */
    public boolean hasMissingAttachment(ItemStack gunStack, IGun gun) {
        try {
            for (AttachmentType type : AttachmentType.values()) {
                if (type == AttachmentType.NONE) {
                    continue;
                }
                if (gun.allowAttachmentType(gunStack, type)
                    && com.tacz.guns.api.DefaultAssets.isEmptyAttachmentId(
                        gun.getAttachmentId(gunStack, type))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    protected void installRandomAttachments(ItemStack gunStack) {
        try {
            IGun gun = IGun.getIGunOrNull(gunStack);
            if (gun == null) {
                return;
            }
            Item attachmentItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("tacz", "attachment"));
            if (attachmentItem == null) {
                return;
            }
            Set<Map.Entry<ResourceLocation, CommonAttachmentIndex>> all =
                TimelessAPI.getAllCommonAttachmentIndex();
            for (AttachmentType type : AttachmentType.values()) {
                if (type == AttachmentType.NONE) {
                    continue;
                }
                if (!gun.allowAttachmentType(gunStack, type)) {
                    continue;
                }
                // 已经装了（内置/继承）就跳过；TACZ 空槽返回 tacz:empty，不能只判 null
                if (!com.tacz.guns.api.DefaultAssets.isEmptyAttachmentId(
                    gun.getAttachmentId(gunStack, type))) {
                    continue;
                }
                List<ItemStack> candidates = new ArrayList<>();
                for (Map.Entry<ResourceLocation, CommonAttachmentIndex> entry : all) {
                    if (entry.getValue().getType() != type
                        || !isAllowedAttachment(entry.getKey())
                        || NpcGunRules.isExplosiveAttachment(entry.getKey())
                        || NpcGunRules.isOverpoweredAttachment(entry.getValue())) {
                        continue;
                    }
                    ItemStack candidate = new ItemStack(attachmentItem);
                    if (candidate.isEmpty() || IGun.getIGunOrNull(candidate) != null) {
                        continue;
                    }
                    try {
                        var attachmentApi = com.tacz.guns.api.item.IAttachment
                            .getIAttachmentOrNull(candidate);
                        if (attachmentApi == null) {
                            continue;
                        }
                        attachmentApi.setAttachmentId(candidate, entry.getKey());
                        // 关键：只挑这把枪真正兼容的配件（allowAttachment 而不是只看类型）
                        if (gun.allowAttachment(gunStack, candidate)) {
                            candidates.add(candidate);
                        }
                    } catch (Throwable ignored) {
                        // 单个配件数据异常不影响其它配件
                    }
                }
                if (candidates.isEmpty()) {
                    continue;
                }
                java.util.Collections.shuffle(candidates, new java.util.Random(this.random.nextLong()));
                // 逐个尝试，直到这个槽真的装上；100% 满配件目标
                for (ItemStack candidate : candidates) {
                    try {
                        gun.installAttachment(gunStack, candidate);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (!com.tacz.guns.api.DefaultAssets.isEmptyAttachmentId(
                        gun.getAttachmentId(gunStack, type))) {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 配件装不上不影响主流程
        }
    }

    /**
     * 清掉 NPC 枪上已经装着的爆炸属性配件（弹药改装/爆炸弹头等）。
     * 在每次装备/切枪时调用，保证旧存档里带爆炸配件的枪也会被纠正。
     */
    public void sanitizeExplosiveAttachments() {
        ItemStack stack = this.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return;
        }
        try {
            for (AttachmentType type : AttachmentType.values()) {
                if (type == AttachmentType.NONE) {
                    continue;
                }
                ResourceLocation id = gun.getAttachmentId(stack, type);
                if (id != null && (NpcGunRules.isExplosiveAttachment(id)
                    || NpcGunRules.isOverpoweredAttachment(id))) {
                    gun.unloadAttachment(stack, type);
                    LOGGER.info("[npc-tacz] removed explosive attachment {} (type={}) from {}",
                        id, type, this.gunId);
                }
            }
        } catch (Throwable ignored) {
            // 配件 API 差异时忽略
        }
    }
    public void equipRandomArmor() {
        for (EquipmentSlot slot : new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            this.setItemSlot(slot, new ItemStack(randomArmorPiece(slot)));
        }
    }

    private Item randomArmorPiece(EquipmentSlot slot) {
        int roll = this.random.nextInt(100);
        boolean soldier = getFaction() == NpcFaction.SOLDIER || getFaction() == NpcFaction.REBEL;
        if (soldier) {
            if (roll < 40) return armorItem("iron", slot);
            if (roll < 75) return armorItem("diamond", slot);
            return armorItem("chainmail", slot);
        }
        if (roll < 50) return armorItem("leather", slot);
        if (roll < 85) return armorItem("chainmail", slot);
        return armorItem("iron", slot);
    }

    private Item armorItem(String material, EquipmentSlot slot) {
        String type = switch (slot) {
            case HEAD -> "helmet";
            case CHEST -> "chestplate";
            case LEGS -> "leggings";
            case FEET -> "boots";
            default -> "helmet";
        };
        return ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft", material + "_" + type));
    }

    public String chooseCategory() {
        return null;
    }

    private static boolean isAllowedType(String type) {
        if (type == null) {
            return false;
        }
        return switch (type.toLowerCase()) {
            case "rifle", "smg", "shotgun", "sniper", "pistol" -> true;
            default -> false;
        };
    }

    public boolean isAllowedAttachment(ResourceLocation id) {
        return true;
    }

    // ------------------------------------------------------------------ 枪械查询

    @Nullable
    public ResourceLocation getGunId() {
        return this.gunId;
    }

    public void setGunId(@Nullable ResourceLocation gunId) {
        this.gunId = gunId;
    }

    @Nullable
    public ResourceLocation getAmmoId() {
        return this.ammoId;
    }

    @Nullable
    public CommonGunIndex getGunIndex() {
        if (this.gunId == null) {
            return null;
        }
        return TimelessAPI.getCommonGunIndex(this.gunId).orElse(null);
    }

    public boolean isShootWarned() {
        return this.shootWarned;
    }

    public void setShootWarned(boolean warned) {
        this.shootWarned = warned;
    }

    // ------------------------------------------------------------------ 掉落

    private static long deathLogTick = Long.MIN_VALUE;
    private static int deathLogCount;

    @Override
    public void die(DamageSource source) {
        if (this.tauntTicks > 0) {
            this.stopTaunt();
        }
        if (!this.level().isClientSide) {
            if (com.npctacz.config.NpcGlobalConfig.getBoolean("npc_voice", true)) {
                this.level().playSound(null, this,
                    net.minecraft.sounds.SoundEvents.PLAYER_DEATH,
                    net.minecraft.sounds.SoundSource.HOSTILE, 0.55F,
                    0.85F + this.getRandom().nextFloat() * 0.25F);
            }
            long now = this.level().getGameTime();
            if (now != deathLogTick) {
                deathLogTick = now;
                deathLogCount = 0;
            }
            // 同一 tick 最多 8 条死亡日志，避免成片死亡刷屏
            if (deathLogCount++ < 8) {
                LOGGER.info("[npc-tacz] NPC died: faction={} killer={}",
                    getFaction(),
                    source.getEntity() == null ? "none" : source.getEntity().getName().getString());
            }
            if (source.getEntity() instanceof LivingEntity killer) {
                com.npctacz.entity.ai.NpcReactionTime.onNpcKilled(this, killer);
                this.alertAllies(killer);
                if (killer instanceof net.minecraft.server.level.ServerPlayer player
                    && com.npctacz.config.NpcGlobalConfig.getBoolean("kill_feed", true)
                    && this.level() instanceof ServerLevel chatLevel
                    && com.npctacz.data.NpcDifficultyData.get(chatLevel)
                        .areChatNotificationsEnabled(player.getUUID())) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "§6击杀 §f" + this.getName().getString() + " §7(" + getFaction().name().toLowerCase(java.util.Locale.ROOT) + ")"
                    ), true);
                }
            }
            this.dropCustomDeathLoot(source, 0, true);
            this.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        super.die(source);
    }

    @Override
    protected void dropCustomDeathLoot(DamageSource source, int looting, boolean recentlyHit) {
        if (this.lootDropped) {
            return;
        }
        this.lootDropped = true;
        this.dropNpcLoot(source, looting, recentlyHit);
        this.dropPickupInventory();
    }

    /**
     * 同阵营增援：NPC 被攻击/死亡时，叫附近同阵营、且对攻击者敌对的单位来支援。
     * 有限半径、最多人数、冷却，避免一次交火把全图拉过来。
     */
    private void alertAllies(LivingEntity attacker) {
        if (this.level().isClientSide || attacker == null || attacker == this
            || !attacker.isAlive() || !this.isHostileTo(attacker)
            || !com.npctacz.entity.ai.NpcCombatHelper.canSee(this, attacker)) {
            return;
        }
        int cooldown = Math.max(20, com.npctacz.config.NpcGlobalConfig.getInt("ally_alert_cooldown", 100));
        if (this.tickCount - this.lastAllyAlertTick < cooldown) {
            return;
        }
        int radius = Math.max(4, com.npctacz.config.NpcGlobalConfig.getInt("ally_alert_radius", 24));
        int max = Math.max(1, com.npctacz.config.NpcGlobalConfig.getInt("ally_alert_max", 4));
        this.lastAllyAlertTick = this.tickCount;
        try {
            java.util.List<WastelandNpcEntity> allies = this.level().getEntitiesOfClass(
                WastelandNpcEntity.class, this.getBoundingBox().inflate(radius),
                ally -> ally != this && ally.isAlive()
                    && ally.getFaction() == this.getFaction()
                    && ally.isHostileTo(attacker));
            allies.sort(java.util.Comparator.comparingDouble(this::distanceToSqr));
            int alerted = 0;
            for (WastelandNpcEntity ally : allies) {
                if (alerted >= max) {
                    break;
                }
                if (ally.receiveAllyAlert(attacker)) {
                    alerted++;
                }
            }
            if (alerted > 0) {
                LOGGER.debug("[npc-tacz] ally alert: faction={} attacker={} alerted={}",
                    getFaction(), attacker.getName().getString(), alerted);
            }
        } catch (Throwable ignored) {
            // 增援失败不影响战斗
        }
    }

    /** 被同阵营叫去支援：只在没有有效目标时接管，避免把正在打的目标强行抢走。 */
    public boolean receiveAllyAlert(LivingEntity attacker) {
        if (this.level().isClientSide || attacker == null || !attacker.isAlive()
            || !this.isHostileTo(attacker)) {
            return false;
        }
        LivingEntity current = this.getTarget();
        if (current != null && current.isAlive() && this.isHostileTo(current)) {
            return false;
        }
        this.retaliationTarget = attacker;
        this.retaliationTicks = Math.max(this.retaliationTicks, 200);
        this.setTarget(attacker);
        return true;
    }

    /** 所有 NPC 基础移速 = 僵尸基准 0.23 × npc_speed_multiplier（默认 1.3）。 */
    private static double configuredBaseSpeed() {
        return ZOMBIE_BASE_SPEED * Math.max(0.1D, Math.min(5.0D,
            com.npctacz.config.NpcGlobalConfig.getDouble("npc_speed_multiplier", 1.3D)));
    }

    /** 管理员速度倍率在 refreshAdminAttributes 里叠加。 */
    private void enforceMovementSpeed() {
        var attribute = this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (attribute != null && this.appliedSpeedMultiplier < 0.0F) {
            attribute.setBaseValue(configuredBaseSpeed());
        }
    }

    /** 子类改变基础生命（例如雇佣兵难度生命）时同步给管理员倍率使用。 */
    protected void setBaseMaxHealth(float value) {
        this.baseMaxHealth = Math.max(1.0F, value);
    }

    /** 默认不随机改名；保留用户/存档原本的名字。只有玩家显式开启 npc_random_names 才生成。 */
    private void applyIdentity() {
        try {
            boolean randomNames = com.npctacz.config.NpcGlobalConfig.getBoolean("npc_random_names", false);
            boolean generated = this.getPersistentData().getBoolean("NpcGeneratedName")
                || (this.getCustomName() != null && NpcNames.looksGenerated(this.getCustomName().getString()));
            if (generated && !randomNames) {
                // 清掉上一版生成的随机名，恢复成原始 NPC 名
                this.setCustomName(null);
                this.setCustomNameVisible(false);
                this.getPersistentData().remove("NpcGeneratedName");
                return;
            }
            if (randomNames && this.getCustomName() == null) {
                this.setCustomName(net.minecraft.network.chat.Component.literal(
                    NpcNames.randomName(this.random, this.getFaction())));
                this.getPersistentData().putBoolean("NpcGeneratedName", true);
            }
            boolean show = randomNames
                && com.npctacz.config.NpcGlobalConfig.getBoolean("npc_name_tags", true);
            this.setCustomNameVisible(show);
        } catch (Throwable ignored) {
        }
    }

    /** 人味：受击踉跄、空闲时只对正面玩家瞥一眼、偶尔停一下。 */
    private void tickHumanBehavior() {
        if (this.level().isClientSide) {
            return;
        }
        if (this.flinchTicks > 0) {
            this.flinchTicks--;
        }
        if (this.idleLookCooldown > 0) {
            this.idleLookCooldown--;
            return;
        }
        this.idleLookCooldown = 30 + this.getRandom().nextInt(50);
        if (this.getHealth() > 0.0F && this.getHealth() < this.getMaxHealth() * 0.3F
            && this.tickCount % 40 == 0
            && (this.getFaction() == NpcFaction.SOLDIER || this.getFaction() == NpcFaction.REBEL)) {
            this.requestLowHealthBackup();
        }
        if (!com.npctacz.config.NpcGlobalConfig.getBoolean("npc_idle_look", true)
            || this.getTarget() != null || this.isAiming() || this.isTaunting()
            || this.isMovementLocked() || this.isDeadOrDying()) {
            return;
        }
        net.minecraft.world.entity.player.Player nearest = this.level().getNearestPlayer(this, 12.0D);
        if (nearest != null && nearest.isAlive() && this.isTargetInFrontArc(nearest, 75.0F)) {
            // 只瞥一眼已经在正面视野里的人，不会凭空发现背后偷袭者。
            this.getLookControl().setLookAt(nearest, 20.0F, 20.0F);
        }
    }

    public int getFlinchTicks() {
        return this.flinchTicks;
    }

    /** 切换目标后先给一段转头时间，未到时间不能攻击。 */
    public boolean isTurnReady() {
        return this.tickCount >= this.turnReadyTick;
    }

    /** 低血量只让士兵/叛军叫附近同阵营来帮忙，不刷聊天栏。 */
    private void requestLowHealthBackup() {
        LivingEntity target = this.getTarget();
        if (target != null && target.isAlive()) {
            this.alertAllies(target);
        }
    }

    /** 发现玩家时给附近玩家一条 actionbar 台词，像真人喊话；有冷却和聊天开关。 */
    public void combatCallout(String line) {
        if (this.level().isClientSide || line == null
            || this.tickCount - this.lastCalloutTick < 200
            || !com.npctacz.config.NpcGlobalConfig.getBoolean("npc_callouts", true)) {
            return;
        }
        this.lastCalloutTick = this.tickCount;
        var component = net.minecraft.network.chat.Component.literal(
            "§7[" + this.getName().getString() + "§7] §f" + line);
        int calloutRadius = Math.max(1,
            com.npctacz.config.NpcGlobalConfig.getInt("npc_callout_radius", 32));
        for (net.minecraft.server.level.ServerPlayer player
            : this.level().getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                this.getBoundingBox().inflate(calloutRadius))) {
            if (com.npctacz.data.NpcDifficultyData.get((net.minecraft.server.level.ServerLevel)
                this.level()).areChatNotificationsEnabled(player.getUUID())) {
                player.displayClientMessage(component, true);
            }
        }
    }

    /** 管理员界面：全局生命/移动速度倍率实时应用到当前 NPC。 */
    public void refreshAdminAttributes(boolean fullHeal) {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        var data = com.npctacz.data.NpcDifficultyData.get(serverLevel);
        float healthMultiplier = (float) (data.getGlobalHealthMultiplier()
            * data.getFactionHealthMultiplier(this.getFaction()));
        var healthAttribute = this.getAttribute(
            net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (healthAttribute != null) {
            float targetBase = this.baseMaxHealth * healthMultiplier;
            float oldMax = this.getMaxHealth();
            if (Math.abs(healthAttribute.getBaseValue() - targetBase) > 1.0E-4F) {
                float ratio = oldMax > 0.0F ? this.getHealth() / oldMax : 1.0F;
                healthAttribute.setBaseValue(targetBase);
                this.setHealth(Math.max(1.0F, Math.min(this.getMaxHealth(), this.getMaxHealth() * ratio)));
            }
            if (fullHeal) {
                this.setHealth(this.getMaxHealth());
            }
            this.appliedHealthMultiplier = healthMultiplier;
        }
        float speedMultiplier = (float) data.getGlobalMovementSpeedMultiplier();
        var speedAttribute = this.getAttribute(
            net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speedAttribute != null) {
            speedAttribute.setBaseValue(configuredBaseSpeed() * speedMultiplier);
            this.appliedSpeedMultiplier = speedMultiplier;
        }
    }

    /** 管理员界面：是否允许掉枪。 */
    protected boolean shouldDropGuns() {
        if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            return com.npctacz.data.NpcDifficultyData.get(serverLevel).isDropGuns();
        }
        return true;
    }

    /** 管理员界面：是否允许掉子弹。 */
    protected boolean shouldDropAmmo() {
        if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            return com.npctacz.data.NpcDifficultyData.get(serverLevel).isDropAmmo();
        }
        return true;
    }

    /** 子类统一入口：掉枪开关在这里判断。 */
    protected void dropGunItem(ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty() || !this.shouldDropGuns()) {
            return;
        }
        this.spawnAtLocation(this.prepareDroppedGun(gunStack));
    }

    protected void dropNpcLoot(DamageSource source, int looting, boolean recentlyHit) {
        if (!com.npctacz.config.NpcGlobalConfig.getBoolean("drop_loot", true)) {
            this.lootDropped = true;
            return;
        }
        super.dropCustomDeathLoot(source, looting, recentlyHit);
        ItemStack gun = this.ammoState.getDropGun();
        this.dropGunItem(gun);
        if (this.ammoId != null) {
            this.dropAmmo(60 + (this.random.nextFloat() < 0.6F ? 60 : 0));
            if (this.random.nextFloat() < 0.2F) {
                this.dropAmmo(64);
            }
        }
        if (this.random.nextFloat() < 0.3F) {
            this.spawnAtLocation(new ItemStack(Items.GOLDEN_APPLE, 1 + this.random.nextInt(2)));
        }
    }

    protected void dropAmmo(int count) {
        if (!this.shouldDropAmmo()) {
            return;
        }
        if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            double multiplier = com.npctacz.data.NpcDifficultyData.get(serverLevel).getAmmoDropMultiplier();
            count = Math.max(0, (int) Math.round(count * multiplier));
        }
        if (count <= 0) {
            return;
        }
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("tacz", "ammo"));
        if (item == null || this.ammoId == null) {
            return;
        }
        int remaining = count;
        while (remaining > 0) {
            int stackSize = Math.min(64, remaining);
            ItemStack stack = new ItemStack(item, stackSize);
            stack.getOrCreateTag().putString("AmmoId", this.ammoId.toString());
            this.spawnAtLocation(stack);
            remaining -= stackSize;
        }
    }

    /** 子类掉落逻辑用：取要掉的枪。 */
    protected ItemStack getGunForDrop() {
        return this.ammoState.getDropGun();
    }

    /** 子类掉落逻辑用：给掉落的枪随机弹匣。 */
    protected ItemStack prepareDroppedGun(ItemStack gunStack) {
        return this.ammoState.prepareDropGun(gunStack);
    }

    // ------------------------------------------------------------------ 听觉

    public void onHeardGunshot(Vec3 position, int interestTicks) {
        if (this.level().isClientSide || this.weakRunning) {
            return;
        }
        this.soundInterestPos = BlockPos.containing(position);
        this.soundInterestUntilTick = this.level().getGameTime() + interestTicks;
    }

    @Nullable
    public BlockPos getSoundInterestPos() {
        return this.soundInterestPos;
    }

    public void refreshSoundInterest(int extraTicks) {
        this.soundInterestUntilTick = Math.max(
            this.soundInterestUntilTick, this.level().getGameTime() + extraTicks
        );
    }

    public void clearSoundInterest() {
        this.soundInterestPos = null;
        this.soundInterestUntilTick = 0L;
    }

    // ------------------------------------------------------------------ 状态查询

    /** 唤醒/解除冻结：清理嘲讽、NoAI、弱运行标记，并重新选最近的玩家为目标。 */
    public void wakeUp() {
        if (this.level().isClientSide) {
            return;
        }
        this.tauntTicks = 0;
        this.entityData.set(DATA_TAUNTING, false);
        this.setNoAi(false);
        this.setPose(net.minecraft.world.entity.Pose.STANDING);
        this.setShiftKeyDown(false);
        this.weakRunning = false;
        this.retaliationTarget = null;
        this.retaliationTicks = 0;
        this.getNavigation().stop();
        Player nearest = this.level().getNearestPlayer(this, 64.0D);
        if (nearest != null && this.isHostileTo(nearest)) {
            this.setTarget(nearest);
        }
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
    }

    public boolean isWeakRunning() {
        return this.weakRunning;
    }

    public BlockPos getHomePos() {
        return this.homePos;
    }

    /**
     * 该 NPC 绑定的通用模型 id；空串表示未绑定。
     * 具体是什么模型由“模型附属”解释；主模组只负责存储/同步/持久化。
     */
    public String getModelId() {
        return this.entityData.get(DATA_MODEL_ID);
    }

    public void setModelId(@Nullable String modelId) {
        this.entityData.set(DATA_MODEL_ID, modelId == null ? "" : modelId);
    }

    /** 单个 NPC 永久指定模型（优先级最高）。 */
    public void setModelExplicit(@Nullable String modelId) {
        this.modelExplicitOverride = true;
        this.setModelId(modelId == null ? "" : modelId);
    }

    public boolean hasExplicitModel() {
        return this.modelExplicitOverride;
    }

    /** 清掉单个指定，允许附属重新套用默认模型。 */
    public void clearExplicitModel() {
        this.modelExplicitOverride = false;
    }

    // ------------------------------------------------------------------ 存档

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        CompoundTag pickupTag = new CompoundTag();
        ContainerHelper.saveAllItems(pickupTag, this.pickupInventory, true);
        tag.put("NpcPickupInventory", pickupTag);
        this.ammoState.save(tag);
        tag.putLong("NpcHome", this.homePos.asLong());
        if (this.gunId != null) tag.putString("NpcGun", this.gunId.toString());
        if (this.ammoId != null) tag.putString("NpcAmmoId", this.ammoId.toString());
        if (this.modelExplicitOverride) {
            tag.putBoolean("NpcModelExplicit", true);
            tag.putString("NpcModel", this.getModelId());
        }
        if (this.tauntTicks > 0) {
            tag.putInt("NpcTauntTicks", this.tauntTicks);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.applyIdentity();
        this.enforceMovementSpeed();
        this.refreshAdminAttributes(false);
        if (tag.contains("NpcPickupInventory")) {
            ContainerHelper.loadAllItems(tag.getCompound("NpcPickupInventory"), this.pickupInventory);
        }
        this.ammoState.load(tag);
        if (tag.contains("NpcHome")) {
            this.homePos = BlockPos.of(tag.getLong("NpcHome"));
        }
        if (tag.contains("NpcGun")) {
            this.gunId = new ResourceLocation(tag.getString("NpcGun"));
        }
        if (tag.contains("NpcAmmoId")) {
            this.ammoId = new ResourceLocation(tag.getString("NpcAmmoId"));
        }
        // 兼容旧键 NpcYsm*，新键为 NpcModel*
        boolean explicitModel = tag.getBoolean("NpcModelExplicit")
            || tag.getBoolean("NpcYsmExplicit");
        String model = tag.contains("NpcModel") ? tag.getString("NpcModel")
            : tag.getString("NpcYsmModel");
        this.modelExplicitOverride = explicitModel;
        this.setModelId(explicitModel ? model : "");
        // 非显式绑定的实体由模型附属在加入世界时套用默认模型
        // Do not leave a saved mid-taunt entity permanently frozen. Resume the
        // remaining crouch-spam time, then normal AI continues.
        if (tag.getInt("NpcTauntTicks") > 0) {
            this.tauntTicks = tag.getInt("NpcTauntTicks");
            this.entityData.set(DATA_TAUNTING, true);
            this.setNoAi(true);
            this.setPose(net.minecraft.world.entity.Pose.CROUCHING);
            this.setShiftKeyDown(true);
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }
}
