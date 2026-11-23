package com.npctacz.entity.ai;

import com.npctacz.config.NpcConfig;
import com.npctacz.entity.WastelandNpcEntity;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.util.time.GunTime;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * NPC 弹药与武器状态机。
 *
 * <p>状态只有两个：</p>
 * <ul>
 *   <li>{@link State#RANGED}：手里拿枪，弹匣有弹就持续射击；弹匣空了从备弹换弹。</li>
 *   <li>{@link State#MELEE}：弹匣和备弹都耗尽，切近战武器冲锋；补弹成功后切回原枪。</li>
 * </ul>
 *
 * <p>关键点：切近战前把当前枪的完整 ItemStack（含配件/枪型）存进 {@code savedGun}，
 * 切回来一定是同一把，不会变成默认步枪；切换时清攻击冷却并停寻路，避免抽搐。</p>
 *
 * <p>射击走 TACZ 官方 {@link IGunOperator} 流程；TACZ 的冷却判定用的是
 * {@code GunTime.nowMillis() - baseTimestamp} 相对时间，所以这里按同样公式把
 * shootTimestamp 推到冷却窗口之前，否则永远返回 COOL_DOWN。</p>
 */
public final class NpcAmmoState {

    public enum State {
        RANGED,
        MELEE,
        /** 弹匣打空、正在换弹：播放换弹动作，期间不能射击。 */
        RELOADING
    }

    private final WastelandNpcEntity npc;

    private State state = State.RANGED;
    /** 弹匣内子弹。 */
    private int magazine;
    /** 备弹（时间补充）。 */
    private int reserve;
    private int refillTimer;
    private int shotCooldown;
    private static net.minecraft.server.level.ServerLevel cachedFireLevel;
    private static long cachedFireTick = Long.MIN_VALUE;
    private static double cachedFireRateMultiplier = 1.0D;
    private int reloadTicks;
    private int reloadTotal;

    /** 切近战前那把枪的完整副本。 */
    private ItemStack savedGun = ItemStack.EMPTY;
    @Nullable
    private ResourceLocation savedGunId;

    private int sustainedShots;
    private int lastShotAge;
    private float recoilPhase;
    private int recoilPauseTicks;
    private int successLogs;
    private int failureLogs;
    /** 线性射击精度：1.0 初始，每枪按难度扣减，低于 0.4 加散布，0.2 以下短暂停火。 */
    private float accuracy = 1.0F;
    private int accuracyPauseTicks;
    private boolean cacheWarned;

    public NpcAmmoState(WastelandNpcEntity npc) {
        this.npc = npc;
    }

    // ------------------------------------------------------------------ 每 tick

    public void tick() {
        if (this.npc.level().isClientSide) {
            return;
        }
        if (this.shotCooldown > 0) {
            this.shotCooldown--;
        }
        this.lastShotAge++;
        if (this.recoilPauseTicks > 0) {
            this.recoilPauseTicks--;
        }
        if (this.accuracyPauseTicks > 0 && --this.accuracyPauseTicks <= 0) {
            this.accuracy = 1.0F;
        }
        if (this.lastShotAge > 20) {
            this.sustainedShots = 0;
            this.recoilPhase = Math.max(0.0F, this.recoilPhase - 0.005F);
        }
        int refillTicks = (this.state == State.MELEE
            ? com.npctacz.config.NpcGlobalConfig.getInt(
                "refill_seconds_melee", NpcConfig.VALUES.refillSecondsMelee.get())
            : com.npctacz.config.NpcGlobalConfig.getInt(
                "refill_seconds_ranged", NpcConfig.VALUES.refillSecondsRanged.get())) * 20;
        // 补弹间隔 /2；每次补弹量 x1.5；上限仍由 getMaxAmmo 控制。
        refillTicks = Math.max(1, refillTicks / 2);
        if (++this.refillTimer >= refillTicks) {
            this.refillTimer = 0;
            int amount = Math.max(1, (int) Math.round(this.npc.getRefillAmount() * 1.5D));
            this.addReserve(amount);
        }

        if (this.state == State.RELOADING) {
            if (--this.reloadTicks <= 0) {
                finishReload();
            }
            return;
        }
        if (this.state != State.RANGED) {
            return;
        }
        if (!hasGunInHand()) {
            if (!tryRestoreGun()) {
                this.switchToMelee();
            }
            return;
        }
        if (this.magazine <= 0) {
            if (this.reserve > 0) {
                startReload();
            } else {
                this.switchToMelee();
            }
        }
    }

    // ------------------------------------------------------------------ 查询

    public State state() {
        return this.state;
    }

    public boolean isRanged() {
        return this.state != State.MELEE;
    }

    /** 是否正在换弹（换弹期间不能射击）。 */
    public boolean isReloading() {
        return this.state == State.RELOADING;
    }

    public boolean isMelee() {
        return this.state == State.MELEE;
    }

    public int magazine() {
        return this.magazine;
    }

    public int reserve() {
        return this.reserve;
    }

    /** 直接设置备弹（addon / 命令用）；设置成功且处于近战时自动切回枪械。 */
    public void setReserve(int amount) {
        this.reserve = Math.max(0, Math.min(this.npc.getMaxAmmo(), amount));
        if (this.state == State.MELEE && this.reserve > 0) {
            this.switchBackToGun();
        }
    }

    public boolean hasGunInHand() {
        return IGun.getIGunOrNull(this.npc.getMainHandItem()) != null;
    }

    public boolean canShoot() {
        LivingEntity target = this.npc.getTarget();
        boolean close = target != null && this.npc.distanceToSqr(target) <= 16.0D * 16.0D;
        boolean accuracyBlocked = this.accuracyPauseTicks > 0
            && (target == null || this.npc.distanceToSqr(target) > 8.0D * 8.0D);
        return this.state == State.RANGED
            && this.magazine > 0
            && this.shotCooldown <= 0
            && (this.recoilPauseTicks <= 0 || close)
            && !accuracyBlocked
            && hasGunInHand();
    }

    /** 当前线性精度（调试/界面用）。 */
    public float accuracy() {
        return this.accuracy;
    }

    public int accuracyPauseTicks() {
        return this.accuracyPauseTicks;
    }

    /** 调试：直接设置弹匣数（0 时按当前备弹决定换弹还是切近战）。 */
    public void debugSetMagazine(int amount) {
        if (this.state != State.RANGED) {
            return;
        }
        ItemStack stack = this.npc.getMainHandItem();
        if (IGun.getIGunOrNull(stack) == null) {
            return;
        }
        int size = magazineSize(stack);
        this.magazine = Math.max(0, Math.min(size, amount));
        applyMagazine(stack, this.magazine);
        if (this.magazine <= 0) {
            if (this.reserve > 0) {
                startReload();
            } else {
                this.switchToMelee();
            }
        }
    }

    /** 低血量 / 换弹间隙 / 空弹匣时需要找掩体。 */
    public boolean needsCover() {
        if (this.state == State.MELEE) {
            return false;
        }
        if (this.state == State.RELOADING) {
            return true;
        }
        float healthRatio = this.npc.getHealth() / Math.max(1.0F, this.npc.getMaxHealth());
        return this.magazine <= 0 || healthRatio < NpcConfig.VALUES.coverHealthThreshold.get();
    }

    public ItemStack gunStack() {
        return this.npc.getMainHandItem();
    }

    // ------------------------------------------------------------------ 射击

    /**
     * 向目标开一枪。{@code extraSpread} 是额外散布（度），移动射击时更大。
     */
    public void shootAt(LivingEntity target, float extraSpread) {
        if (!this.npc.isTurnReady()) {
            return;
        }
        if (target instanceof net.minecraft.world.entity.player.Player
            && !this.npc.isWithinPlayerViewDistance(target)) {
            return;
        }
        ItemStack stack = this.npc.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null || target == null || this.state != State.RANGED || this.magazine <= 0) {
            return;
        }
        try {
            // 旧存档/手动改枪兜底：爆炸枪不参与 NPC 射击，直接换一把普通枪
            CommonGunIndex currentIndex = this.npc.getGunIndex();
            if (NpcGunRules.isExplosiveGun(currentIndex)) {
                WastelandNpcEntity.LOGGER.info(
                    "[npc-tacz] NPC held explosive gun {}, switching to normal gun",
                    this.npc.getGunId());
                this.npc.equipRandomGun();
                this.npc.sanitizeExplosiveAttachments();
                return;
            }
            IGunOperator operator = IGunOperator.fromLivingEntity(this.npc);
            ensureShootCache(operator);
            ShooterDataHolder data = operator.getDataHolder();
            data.setCurrentGunItem(this.npc::getMainHandItem);

            // 把 TACZ 的射击冷却推到窗口之前（它用的是相对 baseTimestamp 的时间）
            long nowRelative = GunTime.nowMillis() - data.baseTimestamp;
            long interval = shootInterval(gun, stack);
            long forcedPast = nowRelative - Math.max(50L, interval) - 10L;
            data.shootTimestamp = forcedPast;
            data.lastShootTimestamp = forcedPast;

            gun.setCurrentAmmoCount(stack, this.magazine);
            gun.setBulletInBarrel(stack, true);

            // 弹道解算：按当前枪械的 speed/gravity/friction 做下坠补偿 + 目标提前量
            Vec3 aim = NpcBallistics.aimPoint(this.npc, target, bulletData());
            Vec3 eye = this.npc.getEyePosition();
            float baseYaw = yawTo(eye.x, eye.z, aim.x, aim.z);
            float basePitch = pitchTo(eye, aim);
            float extraNpcSpread = (float) com.npctacz.config.NpcGlobalConfig.getDouble(
                "npc_extra_spread_degrees", 1.5D);
            float distanceSpread = (float) Math.min(2.0D,
                Math.max(0.0D, Math.sqrt(this.npc.distanceToSqr(target)) - 12.0D) * 0.04D);
            double spreadMultiplier = 1.0D;
            if (this.npc.level() instanceof net.minecraft.server.level.ServerLevel spreadLevel) {
                var spreadData = com.npctacz.data.NpcDifficultyData.get(spreadLevel);
                spreadMultiplier = spreadData.getGlobalSpreadMultiplier()
                    * spreadData.getFactionSpreadMultiplier(this.npc.getFaction());
            }
            float flinchSpread = this.npc.getFlinchTicks() * 0.35F;
            float heatSpread = Math.min(6.0F,
                Math.max(0, this.sustainedShots - 6) * 0.45F);
            float spread = Math.max(0.0F,
                this.npc.getNpcSpreadDegrees() + extraSpread + extraNpcSpread
                    + distanceSpread + flinchSpread + heatSpread)
                * (float) com.npctacz.config.NpcGlobalConfig.getDouble(
                    "accuracy_multiplier", NpcConfig.VALUES.accuracyMultiplier.get())
                * (float) spreadMultiplier;
            int difficulty = this.npc.getNpcDifficulty();
            if (difficulty >= 4) {
                spread *= (float) com.npctacz.config.NpcGlobalConfig.getDouble(
                    "difficulty4_spread_multiplier", 0.65D);
            } else if (difficulty == 3) {
                spread *= (float) com.npctacz.config.NpcGlobalConfig.getDouble(
                    "difficulty3_spread_multiplier", 0.8D);
            }
            if (this.accuracy < 0.4F) {
                // 低于 0.4 才增加散布；线性，不用抛物线。
                spread *= 1.0F + Math.min(0.6F, (0.4F - this.accuracy) * 2.0F);
            }
            float jitterYaw = baseYaw + (this.npc.getRandom().nextFloat() * 2.0F - 1.0F) * spread * 0.5F;
            float jitterPitch = Mth.clamp(
                basePitch + (this.npc.getRandom().nextFloat() * 2.0F - 1.0F) * spread * 0.5F,
                -90.0F, 90.0F
            );
            float aimYaw = jitterYaw;
            float aimPitch = jitterPitch;
            if (com.npctacz.config.NpcGlobalConfig.getBoolean("npc_aim_sway", true)) {
                // 呼吸/持枪微摆，让 NPC 不至于像激光炮台
                float t = (this.npc.tickCount + this.npc.getId() * 17) * 0.09F;
                aimYaw += Mth.sin(t) * 0.18F + Mth.sin(t * 1.7F + 1.3F) * 0.08F;
                aimPitch = Mth.clamp(aimPitch
                    + Mth.cos(t * 0.8F + 0.7F) * 0.12F, -90.0F, 90.0F);
            }
            boolean closeTarget = this.npc.distanceToSqr(target) <= 16.0D * 16.0D;
            if (!closeTarget) {
                if (this.recoilPauseTicks > 0) {
                    return;
                }
                // 竖直后坐力：f(x)=3sin(x)，每发 x += 0.05。
                this.recoilPhase = Math.min((float) Math.PI, this.recoilPhase + 0.05F);
                float climb = 3.0F * Mth.sin(this.recoilPhase);
                // NPC 会压枪：多数情况压回 65%~120%，偶尔不压或压过头。
                float roll = this.npc.getRandom().nextFloat();
                float compensation;
                if (roll < 0.08F) {
                    compensation = 0.0F;
                } else if (roll < 0.16F) {
                    compensation = 2.0F + this.npc.getRandom().nextFloat() * 1.0F;
                } else {
                    compensation = 0.65F + this.npc.getRandom().nextFloat() * 0.55F;
                }
                float vertical = -climb + climb * compensation;
                aimPitch = Mth.clamp(aimPitch + vertical, -90.0F, 90.0F);
                if (climb >= 2.999F) {
                    // 到达顶点：停火 0.1~0.3 秒，再重新开始一整轮后坐力
                    this.recoilPauseTicks = 2 + this.npc.getRandom().nextInt(5);
                    this.recoilPhase = 0.0F;
                }
            } else {
                // 16 格内继续连射，x 不增加，也不会触发停火
                this.recoilPhase = 0.0F;
                this.recoilPauseTicks = 0;
            }
            final float finalYaw = aimYaw;
            final float finalPitch = aimPitch;

            int before = this.magazine;
            ShootResult result;
            try {
                result = operator.shoot(() -> finalPitch, () -> finalYaw);
            } catch (Throwable throwable) {
                if (!this.npc.isShootWarned()) {
                    this.npc.setShootWarned(true);
                    WastelandNpcEntity.LOGGER.error("[npc-tacz] NPC shoot failed", throwable);
                }
                return;
            }

            if (result == ShootResult.SUCCESS) {
                int after = gun.getCurrentAmmoCount(stack);
                if (after < 0) {
                    after = 0;
                }
                this.magazine = Math.max(0, Math.min(after, before - 1));
                double fireRateMultiplier = 1.0D;
                if (this.npc.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    fireRateMultiplier = cachedFireRate(serverLevel);
                }
                int rawRpm = this.npc.getNpcRpm(gun, stack);
                int cappedRpm = com.npctacz.entity.ai.NpcGunRules.cappedNpcRpm(
                    gun.getGunId(stack), rawRpm);
                int rpm = (int) Math.max(1.0D, cappedRpm * fireRateMultiplier);
                this.shotCooldown = Math.max(2, 1200 / rpm);
                this.sustainedShots++;
                this.lastShotAge = 0;
                if (com.npctacz.entity.ai.NpcGunRules.isSniper(gun.getGunId(stack))) {
                    this.shotCooldown += 12 + this.npc.getRandom().nextInt(13);
                }
                this.applyAccuracyDecay(target);
                this.npc.startFiringAnimation();
                if (this.successLogs < 3) {
                    this.successLogs++;
                    WastelandNpcEntity.LOGGER.info("[npc-tacz] shot fired gun={} mag={} target={}",
                        this.npc.getGunId(), this.magazine, target.getName().getString());
                }
                if (this.magazine <= 0) {
                    if (this.reserve > 0) {
                        startReload();
                    } else {
                        this.switchToMelee();
                    }
                }
            } else if (result == ShootResult.NEED_BOLT) {
                operator.bolt();
            } else if (result == ShootResult.NOT_DRAW) {
                operator.draw(this.npc::getMainHandItem);
            } else if (result == ShootResult.NO_AMMO) {
                this.magazine = 0;
                if (this.reserve > 0) {
                    startReload();
                } else {
                    this.switchToMelee();
                }
            }
            if (result != ShootResult.SUCCESS && this.failureLogs < 4) {
                this.failureLogs++;
                WastelandNpcEntity.LOGGER.info("[npc-tacz] shoot result {} gun={} mag={} reserve={}",
                    result, this.npc.getGunId(), this.magazine, this.reserve);
            }
        } catch (Throwable throwable) {
            if (!this.npc.isShootWarned()) {
                this.npc.setShootWarned(true);
                WastelandNpcEntity.LOGGER.error("[npc-tacz] NPC shoot failed", throwable);
            }
        }
    }

    /**
     * TACZ 的 shootOnce 会在 cacheProperty == null 时直接 return（不做弹丸生成），
     * 玩家由 TACZ 自己维护该缓存，NPC 不是玩家所以从来没人给它算过。
     * 这里调用官方 API 补齐：算一次配件/枪械属性缓存，否则永远打不出子弹。
     */
    /**
     * 线性精度衰减：难度越高每枪扣除越少。
     * 难度 1 扣 0.05，难度 2 扣 0.04，难度 3 扣 0.03，难度 4 扣 0.02。
     * 低于 0.2 时远距离目标停火 0.5~1 秒；8 格内继续射击。
     */
    private void applyAccuracyDecay(LivingEntity target) {
        int difficulty = this.npc.getNpcDifficulty();
        float decay = Math.max(0.01F, 0.05F - (difficulty - 1) * 0.01F);
        this.accuracy = Math.max(0.0F, this.accuracy - decay);
        if (this.accuracy <= 0.2F) {
            boolean close = target != null && this.npc.distanceToSqr(target) <= 8.0D * 8.0D;
            if (!close) {
                this.accuracyPauseTicks = 10 + this.npc.getRandom().nextInt(11);
                this.accuracy = 1.0F;
            } else {
                this.accuracy = 0.2F;
            }
        }
    }

    private void ensureShootCache(IGunOperator operator) {
        if (operator.getCacheProperty() != null) {
            return;
        }
        refreshShootCache();
    }

    /** 重新计算枪械属性缓存（换枪/换弹后调用）。 */
    private void refreshShootCache() {
        ItemStack stack = this.npc.getMainHandItem();
        if (IGun.getIGunOrNull(stack) == null) {
            return;
        }
        try {
            AttachmentPropertyManager.postChangeEvent(this.npc, stack);
        } catch (Throwable throwable) {
            if (!this.cacheWarned) {
                this.cacheWarned = true;
                WastelandNpcEntity.LOGGER.error("[npc-tacz] gun property cache failed", throwable);
            }
        }
    }
    private long shootInterval(IGun gun, ItemStack stack) {
        CommonGunIndex index = this.npc.getGunIndex();
        if (index != null && index.getGunData() != null) {
            try {
                return index.getGunData().getShootInterval(
                    this.npc, gun.getFireMode(stack), stack
                );
            } catch (Throwable ignored) {
                return 100L;
            }
        }
        return 100L;
    }

    /** 当前枪械弹道数据；拿不到就返回 null（退回普通瞄准）。 */
    @Nullable
    private static double cachedFireRate(net.minecraft.server.level.ServerLevel level) {
        long now = level.getGameTime();
        if (level != cachedFireLevel || cachedFireTick == Long.MIN_VALUE
            || now < cachedFireTick || now - cachedFireTick > 20L) {
            cachedFireLevel = level;
            cachedFireRateMultiplier = com.npctacz.data.NpcDifficultyData.get(level)
                .getGlobalFireRateMultiplier();
            cachedFireTick = now;
        }
        return cachedFireRateMultiplier;
    }

    private BulletData bulletData() {
        try {
            ResourceLocation gunId = this.npc.getGunId();
            if (gunId == null) {
                return null;
            }
            return TimelessAPI.getCommonGunIndex(gunId)
                .map(CommonGunIndex::getBulletData)
                .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float yawTo(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return (float) (Math.toDegrees(Math.atan2(dz, dx))) - 90.0F;
    }

    private static float pitchTo(Vec3 eye, Vec3 aim) {
        double dx = aim.x - eye.x;
        double dz = aim.z - eye.z;
        double dy = aim.y - eye.y;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) (-Math.toDegrees(Math.atan2(dy, Math.max(1.0E-4D, horizontal))));
    }

    // ------------------------------------------------------------------ 补弹与换弹

    public void addReserve(int amount) {
        if (amount <= 0) {
            return;
        }
        int before = this.reserve;
        this.reserve = Math.min(this.npc.getMaxAmmo(), this.reserve + amount);
        if (this.reserve > before && this.state == State.MELEE) {
            this.switchBackToGun();
        }
    }

    /** 弹匣打空：进入换弹状态（播放换弹动作，期间不能射击）。 */
    private void startReload() {
        if (this.state != State.RANGED) {
            return;
        }
        if (IGun.getIGunOrNull(this.npc.getMainHandItem()) == null) {
            this.switchToMelee();
            return;
        }
        this.reloadTotal = 40;
        this.reloadTicks = this.reloadTotal;
        this.state = State.RELOADING;
        this.sustainedShots = 0;
        this.recoilPhase = 0.0F;
        this.recoilPauseTicks = 0;
        this.npc.startReloadAnimation();
    }

    /** 换弹完成：从备弹装满弹匣。 */
    private void finishReload() {
        this.reloadTicks = 0;
        ItemStack stack = this.npc.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            this.switchToMelee();
            return;
        }
        int size = magazineSize(stack);
        int load = Math.min(size, Math.max(0, this.reserve));
        if (load <= 0) {
            this.state = State.RANGED;
            this.magazine = 0;
            this.switchToMelee();
            return;
        }
        this.reserve -= load;
        this.magazine = load;
        applyMagazine(stack, load);
        this.state = State.RANGED;
        this.npc.sanitizeExplosiveAttachments();
    }

    // ------------------------------------------------------------------ 武器切换

    /** 远程满弹初始化（生成 / 重新装备枪时调用）。 */
    public void onGunEquipped() {
        ItemStack stack = this.npc.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            this.magazine = 0;
            this.reserve = Math.max(this.reserve, this.npc.getMaxAmmo());
            return;
        }
        this.magazine = magazineSize(stack);
        this.reserve = this.npc.getMaxAmmo();
        this.state = State.RANGED;
        this.savedGun = ItemStack.EMPTY;
        this.savedGunId = null;
        this.refillTimer = 0;
        this.shotCooldown = 0;
        applyMagazine(stack, this.magazine);
        this.reloadTicks = 0;
        this.npc.sanitizeExplosiveAttachments();
        refreshShootCache();
    }

    /** 切近战：记住当前枪 → 换近战武器 → 清冷却。 */
    public void switchToMelee() {
        if (this.npc.level().isClientSide || this.state == State.MELEE) {
            return;
        }
        ItemStack current = this.npc.getMainHandItem();
        IGun currentGun = current.isEmpty() ? null : IGun.getIGunOrNull(current);
        if (currentGun != null) {
            this.savedGun = current.copy();
            ResourceLocation id = currentGun.getGunId(current);
            this.savedGunId = id != null ? id : this.npc.getGunId();
            if (this.savedGunId != null) {
                this.npc.setGunId(this.savedGunId);
            }
        } else if (this.npc.getGunId() != null && this.savedGunId == null) {
            this.savedGunId = this.npc.getGunId();
        }
        this.state = State.MELEE;
        this.reloadTicks = 0;
        ItemStack melee = meleeWeaponStack();
        if (!melee.isEmpty()) {
            this.npc.setItemInHand(InteractionHand.MAIN_HAND, melee);
        }
        this.shotCooldown = 0;
        this.npc.afterWeaponSwitch();
        WastelandNpcEntity.LOGGER.info("[npc-tacz] state RANGED -> MELEE (savedGun={} mag={} reserve={})",
            this.savedGunId, this.magazine, this.reserve);
    }

    /** 切回枪械：恢复切近战前那把枪 + 从备弹装弹匣。 */
    public void switchBackToGun() {
        if (this.npc.level().isClientSide || this.state != State.MELEE || this.reserve <= 0) {
            return;
        }
        ItemStack gunStack = restoreSavedGun();
        if (gunStack.isEmpty()) {
            // 没有“切近战前那把枪”的记录（生成时装备失败等）：现场补一把
            this.npc.equipRandomGun();
            if (!hasGunInHand()) {
                this.npc.equipKnownGun();
            }
            gunStack = restoreSavedGun();
        }
        if (gunStack.isEmpty() || IGun.getIGunOrNull(gunStack) == null) {
            WastelandNpcEntity.LOGGER.info("[npc-tacz] switch back failed: no valid saved gun");
            return;
        }
        int size = magazineSize(gunStack);
        int load = Math.min(size, this.reserve);
        this.reserve -= load;
        this.magazine = load;
        applyMagazine(gunStack, load);
        this.npc.setItemInHand(InteractionHand.MAIN_HAND, gunStack);
        this.npc.sanitizeExplosiveAttachments();
        this.savedGun = ItemStack.EMPTY;
        this.savedGunId = null;
        this.state = State.RANGED;
        this.refillTimer = 0;
        this.shotCooldown = 0;
        this.reloadTicks = 0;
        this.npc.startReloadAnimation();
        this.npc.afterWeaponSwitch();
        WastelandNpcEntity.LOGGER.info("[npc-tacz] state MELEE -> RANGED gun={} mag={} reserve={}",
            this.npc.getGunId(), this.magazine, this.reserve);
    }

    private ItemStack restoreSavedGun() {
        if (!this.savedGun.isEmpty()) {
            return this.savedGun.copy();
        }
        if (this.savedGunId != null) {
            ItemStack stack = this.npc.createGunStack(this.savedGunId);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        if (this.npc.getGunId() != null) {
            ItemStack stack = this.npc.createGunStack(this.npc.getGunId());
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean tryRestoreGun() {
        if (hasGunInHand()) {
            return true;
        }
        ItemStack restored = restoreSavedGun();
        if (restored.isEmpty()) {
            return false;
        }
        applyMagazine(restored, Math.max(1, this.magazine));
        this.npc.setItemInHand(InteractionHand.MAIN_HAND, restored);
        return true;
    }

    private ItemStack meleeWeaponStack() {
        String id = NpcConfig.VALUES.meleeWeapon.get();
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        if (item == null) {
            item = Items.IRON_SWORD;
        }
        return new ItemStack(item);
    }

    // ------------------------------------------------------------------ 弹匣工具

    public int magazineSize(ItemStack gunStack) {
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null || gunStack.isEmpty()) {
            return 30;
        }
        ResourceLocation id = gun.getGunId(gunStack);
        CommonGunIndex index = id == null ? null : TimelessAPI.getCommonGunIndex(id).orElse(null);
        if (index != null && index.getGunData() != null) {
            int size = index.getGunData().getAmmoAmount();
            if (size > 0) {
                return size;
            }
        }
        return 30;
    }

    public void applyMagazine(ItemStack gunStack, int amount) {
        if (gunStack.isEmpty()) {
            return;
        }
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null) {
            return;
        }
        int value = Math.max(0, amount);
        gun.setCurrentAmmoCount(gunStack, value);
        gun.setBulletInBarrel(gunStack, value > 0);
    }

    // ------------------------------------------------------------------ 掉落

    /** 掉落用的枪：远程状态取主手，近战状态取记住的枪。 */
    public ItemStack getDropGun() {
        ItemStack hand = this.npc.getMainHandItem();
        if (IGun.getIGunOrNull(hand) != null) {
            return hand;
        }
        if (!this.savedGun.isEmpty()) {
            return this.savedGun;
        }
        return ItemStack.EMPTY;
    }

    /** 掉落枪随机塞 50%~100% 弹匣。 */
    public ItemStack prepareDropGun(ItemStack gunStack) {
        if (gunStack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack drop = gunStack.copy();
        NpcGunRules.applyBestFireMode(drop);
        int size = magazineSize(drop);
        float ratio = 0.5F + this.npc.getRandom().nextFloat() * 0.5F;
        int loaded = Math.max(1, Math.min(size, Math.round(size * ratio)));
        applyMagazine(drop, loaded);
        return drop;
    }

    // ------------------------------------------------------------------ 存档

    public void save(CompoundTag tag) {
        tag.putInt("NpcMagazine", this.magazine);
        tag.putInt("NpcReserve", this.reserve);
        tag.putByte("NpcAmmoState", (byte) this.state.ordinal());
        tag.putInt("NpcRefill", this.refillTimer);
        if (this.savedGunId != null) {
            tag.putString("NpcSavedGunId", this.savedGunId.toString());
        }
        if (!this.savedGun.isEmpty()) {
            tag.put("NpcSavedGun", this.savedGun.save(new CompoundTag()));
        }
    }

    public void load(CompoundTag tag) {
        boolean hasMag = tag.contains("NpcMagazine");
        this.magazine = hasMag ? Math.max(0, tag.getInt("NpcMagazine")) : 0;
        this.reserve = Math.max(0, tag.getInt("NpcReserve"));
        if (!tag.contains("NpcReserve")) {
            this.reserve = Math.max(0, tag.getInt("NpcAmmo"));
        }
        byte ordinal = tag.contains("NpcAmmoState") ? tag.getByte("NpcAmmoState") : 0;
        int stateIndex = Math.max(0, Math.min(State.values().length - 1, ordinal));
        this.state = State.values()[stateIndex];
        // 读档时不要停留在“换弹中”，下一 tick 会按弹匣/备弹重新决定
        if (this.state == State.RELOADING) {
            this.state = State.RANGED;
        }
        this.refillTimer = Math.max(0, tag.getInt("NpcRefill"));
        if (tag.contains("NpcSavedGun")) {
            ItemStack saved = ItemStack.of(tag.getCompound("NpcSavedGun"));
            this.savedGun = saved.isEmpty() ? ItemStack.EMPTY : saved;
        }
        if (tag.contains("NpcSavedGunId")) {
            this.savedGunId = new ResourceLocation(tag.getString("NpcSavedGunId"));
        }
        // 旧存档 / 迁移兜底：备弹为 0 或没有弹匣字段时按满弹处理，避免一进游戏就切近战
        if (!hasMag || (this.state == State.RANGED && this.reserve <= 0)) {
            if (this.state == State.RANGED) {
                onGunEquipped();
            }
        }
    }
}
