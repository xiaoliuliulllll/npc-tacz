package com.npctacz.entity;

import com.npctacz.data.NpcDifficultyData;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Mercenary.
 *
 * <p>Before hiring it is neutral to everything except hostile monsters. A player
 * can hire it with an emerald; after that it follows/stays on right click,
 * protects the owner, and fights NPCs/players that are hostile to the owner.</p>
 */
public class MercenaryNpc extends SoldierNpc {
    private static final int INFINITE_AMMO = 1_000_000;

    @Nullable
    private UUID ownerUUID;
    private boolean following = true;
    /** 强制停留锚点（停留模式记录当前位置）。 */
    @Nullable
    private net.minecraft.core.BlockPos stayPos;

    public MercenaryNpc(EntityType<? extends MercenaryNpc> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 38.0D / 1.5D)
            .add(Attributes.MOVEMENT_SPEED, 0.09D)
            .add(Attributes.ATTACK_DAMAGE, 2.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    public NpcFaction getFaction() {
        return NpcFaction.MERCENARY;
    }

    private int difficulty() {
        if (this.level() instanceof ServerLevel serverLevel) {
            return NpcDifficultyData.get(serverLevel).getDifficulty();
        }
        return 2;
    }

    @Override
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(
            net.minecraft.world.level.ServerLevelAccessor level,
            net.minecraft.world.DifficultyInstance difficulty,
            net.minecraft.world.entity.MobSpawnType reason,
            net.minecraft.world.entity.SpawnGroupData spawnData,
            net.minecraft.nbt.CompoundTag dataTag) {
        net.minecraft.world.entity.SpawnGroupData data =
            super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        // 雇佣兵基础血量整体除以 1.5；这里保留难度差异，再交给管理员生命倍率。
        float health = (float) (switch (this.difficulty()) {
            case 1 -> 38.0D;
            case 3 -> 52.0D;
            case 4 -> 60.0D;
            default -> 46.0D;
        } / 1.5D);
        this.setBaseMaxHealth(health);
        var maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
            this.refreshAdminAttributes(true);
        }
        return data;
    }

    /** 雇佣兵不自动拾取地上的物品。 */
    @Override
    protected boolean canPickUpItems() {
        return false;
    }

    @Override
    public boolean canRetaliateAgainst(LivingEntity attacker) {
        if (attacker instanceof Player player && this.isOwner(player)) {
            return false;
        }
        return super.canRetaliateAgainst(attacker);
    }

    @Override
    public int getInitialAmmo() {
        return INFINITE_AMMO;
    }

    @Override
    public int getMaxAmmo() {
        return INFINITE_AMMO;
    }

    @Override
    public int getRefillAmount() {
        return INFINITE_AMMO;
    }

    // ------------------------------------------------------------------ 招募 / 跟随

    @Nullable
    public Player getOwner() {
        if (this.ownerUUID == null || !(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        return serverLevel.getServer().getPlayerList().getPlayer(this.ownerUUID);
    }

    public boolean isOwner(Player player) {
        return player != null && this.ownerUUID != null && this.ownerUUID.equals(player.getUUID());
    }

    public boolean isHired() {
        return this.ownerUUID != null;
    }

    public boolean isFollowing() {
        return this.following;
    }

    public boolean isForcedStay() {
        return this.isHired() && !this.following;
    }

    @Nullable
    public net.minecraft.core.BlockPos getStayPos() {
        return this.stayPos;
    }

    @Override
    public boolean isMovementLocked() {
        return this.isForcedStay();
    }

    public void setOwner(Player player) {
        this.ownerUUID = player.getUUID();
        this.following = true;
        this.setPersistenceRequired();
        this.setTarget(null);
        this.setHealth(this.getMaxHealth());
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!this.isHired()) {
            if (!stack.is(Items.EMERALD)) {
                player.displayClientMessage(Component.literal("需要绿宝石才能招募雇佣兵"), true);
                return InteractionResult.PASS;
            }
            stack.shrink(1);
            this.setOwner(player);
            this.playSound(SoundEvents.VILLAGER_YES, 1.0F, 1.0F);
            player.displayClientMessage(Component.literal("雇佣兵已加入，右键切换跟随/停留"), true);
            return InteractionResult.SUCCESS;
        }
        if (this.isOwner(player)) {
            this.following = !this.following;
            this.getNavigation().stop();
            if (this.following) {
                this.stayPos = null;
            } else {
                // 强制停留：记住当前位置，之后不再移动
                this.stayPos = this.blockPosition();
            }
            player.displayClientMessage(Component.literal(
                this.following ? "雇佣兵：跟随" : "雇佣兵：强制停留（原地不移动）"
            ), true);
            return InteractionResult.SUCCESS;
        }
        player.displayClientMessage(Component.literal("这名雇佣兵已经有雇主了"), true);
        return InteractionResult.PASS;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || !this.isHired()) {
            return;
        }
        Player owner = this.getOwner();
        if (owner == null || owner.level() != this.level()) {
            return;
        }

        // Protect the owner: immediately retaliate against whoever hurt them.
        LivingEntity ownerAttacker = owner.getLastHurtByMob();
        if (ownerAttacker != null && ownerAttacker.isAlive() && this.isHostileTo(ownerAttacker)) {
            this.setTarget(ownerAttacker);
        }

        if (!this.following || this.getTarget() != null) {
            return;
        }
        if (this.distanceToSqr(owner) > 9.0D && this.tickCount % 10 == 0) {
            this.navigator().moveTo(owner, 1.0D);
        }
    }

    // ------------------------------------------------------------------ 敌我

    @Override
    public boolean isHostileTo(LivingEntity other) {
        if (other == this || other == null || !other.isAlive()) return false;
        if (this.isOwner(other instanceof Player player ? player : null)) return false;

        if (!this.isHired()) {
            // Before hiring: neutral to players/NPCs, but still fights hostile monsters.
            return other instanceof Enemy;
        }

        if (other instanceof Player player) {
            Player owner = this.getOwner();
            if (owner == null) return false;
            return owner.getLastHurtByMob() == player || this.getLastHurtByMob() == player;
        }
        if (other instanceof WastelandNpcEntity npc) {
            if (npc.getFaction() == NpcFaction.MERCENARY) return false;
            if (npc.getFaction() == NpcFaction.BOUNTY_HUNTER) {
                return this.isOwnerAttacker(npc);
            }
            // Soldier/Bandit/Rebel are hostile to the player owner.
            return true;
        }
        return other instanceof Enemy || other == this.getLastHurtByMob();
    }

    private boolean isOwnerAttacker(LivingEntity other) {
        Player owner = this.getOwner();
        return owner != null && (owner.getLastHurtByMob() == other || this.getLastHurtByMob() == other);
    }

    // ------------------------------------------------------------------ 伤害

    @Override
    public float modifySoldierDamage(LivingEntity victim, float rawGunDamage) {
        int d = this.difficulty();
        CommonGunIndex index = this.getGunIndex();
        boolean sniper = index != null && "sniper".equalsIgnoreCase(index.getType());

        float amount;
        if (victim instanceof Player player) {
            if (this.isOwner(player)) return 0.0F;
            amount = sniper
                ? (5.0F + 6.0F * (d - 1)) + this.random.nextFloat() * 5.0F
                : switch (d) {
                    case 1 -> 0.5F + this.random.nextFloat();
                    case 2 -> 1.5F + this.random.nextFloat() * 1.5F;
                    case 3 -> 3.5F + this.random.nextFloat() * 1.5F;
                    default -> 6.5F + this.random.nextFloat() * 3.0F;
                };
            // Hostile players take 40% less damage from the mercenary.
            amount *= 0.6F;
        } else {
            float factor = switch (d) {
                case 1 -> 0.6F;
                case 2 -> 0.8F;
                case 3 -> 1.0F;
                default -> 1.5F;
            };
            amount = rawGunDamage * factor;
        }

        float armor = victim.getArmorValue();
        float penetration = switch (d) {
            case 1 -> 0.10F;
            case 2 -> 0.20F;
            case 3 -> 0.45F;
            default -> 0.60F;
        };
        amount *= 1.0F + Math.min(20.0F, armor) * 0.04F * penetration;

        double distance = this.distanceTo(victim);
        double range = this.getAttackRange();
        if (distance > range) {
            amount *= Math.max(0.15F, (float) (range / distance));
        }
        return amount;
    }

    // ------------------------------------------------------------------ 存档

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.ownerUUID != null) tag.putUUID("MercOwner", this.ownerUUID);
        tag.putBoolean("MercFollowing", this.following);
        if (this.stayPos != null) tag.putLong("MercStayPos", this.stayPos.asLong());
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("MercOwner")) this.ownerUUID = tag.getUUID("MercOwner");
        if (tag.contains("MercFollowing")) this.following = tag.getBoolean("MercFollowing");
        if (tag.contains("MercStayPos")) this.stayPos = net.minecraft.core.BlockPos.of(tag.getLong("MercStayPos"));
    }
}
