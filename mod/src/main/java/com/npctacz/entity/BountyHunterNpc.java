package com.npctacz.entity;

import com.npctacz.data.NpcDifficultyData;
import com.npctacz.entity.ai.NpcGunRules;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bounty hunter with per-difficulty values and special targeting. The AI, pathfinding, shelter and breach
 * code stays in WastelandNpcEntity; only this NPC's numbers are changed here.
 */
public class BountyHunterNpc extends WastelandNpcEntity {
    /** 自然存在 2 天（24000 tick/天）后消失；消失不产生掉落物。 */
    private static final long LIFETIME_TICKS = 2L * 24000L;
    private int deathsRemaining = 2;
    private long spawnedAtTick = -1L;
    @javax.annotation.Nullable private java.util.UUID trackedPlayer;
    public BountyHunterNpc(EntityType<? extends BountyHunterNpc> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 15.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.09D)
            .add(Attributes.ATTACK_DAMAGE, 2.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    public NpcFaction getFaction() {
        return NpcFaction.BOUNTY_HUNTER;
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
        int d = this.difficulty();
        // 赏金猎人基础血量整体 x0.5；保留难度差异，并让管理员生命倍率继续生效。
        float health = (float) (switch (d) {
            case 1 -> 26.0D;
            case 3 -> 38.0D;
            case 4 -> 46.0D;
            default -> 30.0D;
        } * 0.5D);
        this.setBaseMaxHealth(health);
        var maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
            this.refreshAdminAttributes(true);
        }
        var followRange = this.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(Math.max(32.0D, Math.min(64.0D, getAttackRange())));
        }
        this.deathsRemaining = 2 + (this.difficulty() - 1);
        return data;
    }

    @Override
    public int getInitialAmmo() {
        return switch (difficulty()) {
            case 1 -> 15;
            case 3 -> 45;
            case 4 -> 60;
            default -> 30;
        };
    }

    @Override
    public int getMaxAmmo() {
        return getInitialAmmo();
    }

    @Override
    public int getRefillAmount() {
        return switch (difficulty()) {
            case 1 -> 1;
            case 3 -> 1;
            case 4 -> 1;
            default -> 1;
        };
    }

    @Override
    public int getNpcRpm(IGun gun, ItemStack stack) {
        int base = Math.max(1, gun.getRPM(stack));
        return switch (difficulty()) {
            case 1 -> Math.max(100, base - 300);
            case 2 -> base < 200 ? base : Math.max(200, base - 100);
            case 3 -> base + 20;
            case 4 -> base + 50;
            default -> base;
        };
    }

    @Override
    public float getNpcSpreadDegrees() {
        int d = difficulty();
        boolean sniper = isSniper();
        float accuracy = sniper
            ? 1.1F + 0.2F * (d - 1)
            : switch (d) {
                case 1 -> 0.7F;
                case 3 -> 1.2F;
                case 4 -> 1.4F;
                default -> 1.0F;
            };
        return (sniper ? 4.0F : 6.0F) / Math.max(0.1F, accuracy);
    }

    @Override
    public double getAttackRange() {
        int d = difficulty();
        String type = gunType();
        if (type == null) return this.rangeAdjusted(32.0D);
        return this.rangeAdjusted(switch (type) {
            case "pistol", "shotgun" -> switch (d) {
                case 1 -> 16.0D;
                case 2 -> 32.0D;
                case 3 -> 40.0D;
                default -> 64.0D;
            };
            case "smg" -> switch (d) {
                case 1 -> 24.0D;
                case 2 -> 48.0D;
                case 3 -> 48.0D;
                default -> 64.0D;
            };
            case "rifle" -> switch (d) {
                case 1 -> 32.0D;
                case 2 -> 64.0D;
                case 3 -> 72.0D;
                default -> 96.0D;
            };
            case "mg" -> switch (d) {
                case 1 -> 24.0D;
                case 2 -> 70.0D;
                case 3 -> 80.0D;
                default -> 108.0D;
            };
            case "sniper" -> switch (d) {
                case 1 -> 40.0D;
                case 2 -> 96.0D;
                case 3 -> 96.0D;
                default -> 96.0D;
            };
            case "heavy" -> switch (d) {
                case 1 -> 32.0D;
                case 2 -> 64.0D;
                case 3 -> 82.0D;
                default -> 128.0D;
            };
            default -> 32.0D;
        });
    }

    @Override
    public double getTargetScanRange() {
        return Math.max(256.0D, getAttackRange());
    }

    @Override
    public double getAwarenessRange() {
        return this.rangeAdjusted(256.0D);
    }

    @Override
    public boolean isHostileTo(LivingEntity other) {
        if (other == this || other == null || !other.isAlive()) return false;
        // 赏金猎人：敌对生物 > 玩家 > 其他 NPC。
        if (other instanceof net.minecraft.world.entity.monster.Enemy) {
            return true;
        }
        if (other instanceof WastelandNpcEntity npc) {
            return npc != this && npc.getFaction() != getFaction();
        }
        if (other instanceof Player player) {
            // 默认只追生存玩家，可用全局配置放开创造/旁观
            if (!this.allowsCreativePlayers() && player.isCreative()) {
                return false;
            }
            if (!this.allowsSpectatorPlayers() && player.isSpectator()) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 赏金猎人只追玩家：索敌本身由 NpcTargetGoal + NpcCombatHelper 处理，
     * 这里不需要单独覆盖 findNearestEnemy。
     */
    /**
     * 全中立：不接受任何非玩家目标（也不反击怪物），创造/旁观玩家也不追。
     */
    @Override
    public void setTarget(@javax.annotation.Nullable LivingEntity target) {
        if (target != null && !(target instanceof Player)
            && !(target instanceof net.minecraft.world.entity.monster.Enemy)
            && !(target instanceof WastelandNpcEntity)) {
            return;
        }
        if (target instanceof Player player) {
            if ((!this.allowsCreativePlayers() && player.isCreative())
                || (!this.allowsSpectatorPlayers() && player.isSpectator())) {
                return;
            }
        }
        super.setTarget(target);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;
        if (this.tickCount % 20 == 0) {
                Player nearest = this.level().getNearestPlayer(this, getAwarenessRange());
            if (nearest != null) {
                this.trackedPlayer = nearest.getUUID();
            }
        }
        if (this.spawnedAtTick < 0L) {
            this.spawnedAtTick = this.level().getGameTime();
        }
        if (this.level().getGameTime() - this.spawnedAtTick >= LIFETIME_TICKS) {
            // 过期自然消失：discard 不会触发死亡掉落
            this.discard();
            return;
        }
        if (this.deathsRemaining <= 0) {
            this.discard();
            return;
        }
        LivingEntity target = this.getTarget();
        if (target instanceof Player && this.distanceToSqr(target) > 9.0D
            && this.tickCount % 20 == 0 && this.getNavigation().isDone()) {
            this.getNavigation().moveTo(target, 1.0D);
        }
    }

    public void onTrackedPlayerDeath(Player player) {
        if (this.deathsRemaining <= 0 || this.trackedPlayer == null) return;
        if (this.trackedPlayer.equals(player.getUUID()) || this.getTarget() == player) {
            this.deathsRemaining--;
            if (this.deathsRemaining <= 0) this.discard();
        }
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("BountyDeathsRemaining", this.deathsRemaining);
        tag.putLong("BountySpawnedAt", this.spawnedAtTick);
        if (this.trackedPlayer != null) tag.putUUID("BountyTrackedPlayer", this.trackedPlayer);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("BountyDeathsRemaining")) this.deathsRemaining = tag.getInt("BountyDeathsRemaining");
        if (tag.contains("BountySpawnedAt")) this.spawnedAtTick = tag.getLong("BountySpawnedAt");
        if (tag.hasUUID("BountyTrackedPlayer")) this.trackedPlayer = tag.getUUID("BountyTrackedPlayer");
    }

    @Override
    public String chooseCategory() {
        int roll = this.random.nextInt(100);
        int d = difficulty();
        if (d <= 2) {
            if (roll < 5) return "rifle";
            if (roll < 10) return "smg";
            if (roll < 70) return "sniper";
            return "pistol";
        }
        if (roll < 5) return "rifle";
        if (roll < 10) return "smg";
        if (roll < 90) return "sniper";
        return "pistol";
    }

    @Override
    public void equipRandomGun() {
        try {
            int d = difficulty();
            String wanted = chooseCategory();
            boolean sniperCategory = "sniper".equals(wanted);
            float minDamage = sniperCategory ? 10.0F + 10.0F * (d - 1) : switch (d) {
                case 1 -> 5.0F;
                case 2 -> 7.5F;
                case 3 -> 11.0F;
                default -> 14.0F;
            };
            float maxDamage = sniperCategory ? 40.0F + 10.0F * (d - 1) : switch (d) {
                case 1 -> 15.0F;
                case 2 -> 20.0F;
                case 3 -> 30.0F;
                default -> 35.0F;
            };

            List<Map.Entry<ResourceLocation, CommonGunIndex>> pool = new ArrayList<>();
            for (Map.Entry<ResourceLocation, CommonGunIndex> entry : TimelessAPI.getAllCommonGunIndex()) {
                CommonGunIndex index = entry.getValue();
                if (index == null || index.getGunData() == null || index.getBulletData() == null) continue;
                String type = index.getType();
                if (type == null || !type.equalsIgnoreCase(wanted)) continue;
                if (NpcGunRules.isExplosiveId(entry.getKey()) || NpcGunRules.isExplosiveGun(entry.getValue())) continue;
                float damage = index.getBulletData().getDamageAmount();
                if (damage >= minDamage && damage <= maxDamage) {
                    pool.add(entry);
                }
            }
            if (pool.isEmpty()) {
                // Keep the NPC usable if a gun pack uses unusual damage values.
                for (Map.Entry<ResourceLocation, CommonGunIndex> entry : TimelessAPI.getAllCommonGunIndex()) {
                    CommonGunIndex index = entry.getValue();
                    if (index == null || index.getType() == null || !index.getType().equalsIgnoreCase(wanted)) continue;
                    if (!NpcGunRules.isExplosiveId(entry.getKey()) && !NpcGunRules.isExplosiveGun(entry.getValue())) pool.add(entry);
                }
            }
            if (pool.isEmpty()) return;

            Map.Entry<ResourceLocation, CommonGunIndex> selected = pool.get(this.random.nextInt(pool.size()));
            this.gunId = selected.getKey();
            this.ammoId = selected.getValue().getGunData().getAmmoId();
            ItemStack stack = createGunStack(this.gunId);
            if (stack.isEmpty()) return;
            installRandomAttachments(stack);
            this.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
        } catch (Throwable throwable) {
            this.onGunEquipError(throwable);
        }
    }

    @Override
    public boolean isAllowedAttachment(ResourceLocation id) {
        return !NpcGunRules.isExplosiveAttachmentId(id);
    }

    @Override
    public void equipRandomArmor() {
        int d = difficulty();
        for (EquipmentSlot slot : new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            int roll = this.random.nextInt(100);
            String material;
            if (d == 1) {
                material = roll < 50 ? "leather" : "chainmail";
            } else {
                material = roll < 50 ? "iron" : "chainmail";
            }
            String type = switch (slot) {
                case HEAD -> "helmet";
                case CHEST -> "chestplate";
                case LEGS -> "leggings";
                default -> "boots";
            };
            this.setItemSlot(slot, new ItemStack(
                ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft", material + "_" + type))
            ));
        }
    }

    private String gunType() {
        CommonGunIndex index = getGunIndex();
        return index == null || index.getType() == null ? null : index.getType().toLowerCase();
    }

    private boolean isSniper() {
        return "sniper".equals(gunType());
    }

    private static boolean isExplosiveId(ResourceLocation id) {
        if (id == null) return false;
        String s = id.toString().toLowerCase();
        return s.contains("explosive") || s.contains("ammo_mod_he")
            || s.contains("grenade") || s.contains("rpg") || s.contains("rocket")
            || s.contains("launcher") || s.contains("m320") || s.contains("m79")
            || s.contains("gp25") || s.contains("thumper") || s.contains("40mm");
    }

    /**
     * Called only from the bandit damage event. The AI still fires the gun;
     * this only replaces the final damage number and armor penetration.
     */
    public float modifyBountyHunterDamage(LivingEntity victim, float rawGunDamage) {
        int d = difficulty();
        String type = gunType();
        boolean sniper = "sniper".equals(type);
        boolean pistol = "pistol".equals(type);
        float amount;
        if (victim instanceof Player) {
            if (pistol) {
                amount = 19.0F + 10.0F * (d - 1);
            } else if (sniper) {
                amount = d == 1 ? 14.0F : Math.min(23.0F, 16.0F + 3.0F * (d - 2));
            } else {
                amount = switch (d) {
                    case 1 -> 0.5F + this.random.nextFloat();
                    case 2 -> 1.5F + this.random.nextFloat() * 1.5F;
                    case 3 -> 3.5F + this.random.nextFloat() * 1.5F;
                    default -> 6.5F + this.random.nextFloat() * 3.0F;
                };
            }
        } else {
            if (pistol) {
                amount = 20.0F + 8.0F * (d - 1) + rawGunDamage * 2.0F;
            } else if (sniper) {
                amount = 30.0F + 16.0F * (d - 1) + rawGunDamage;
            } else {
                float factor = switch (d) {
                    case 1 -> 0.1F;
                    case 2 -> 0.45F;
                    case 3 -> 0.6F;
                    default -> 1.3F;
                };
                amount = rawGunDamage * factor;
            }
        }
        float armor = victim.getArmorValue();
        float penetration = (pistol || sniper) ? 0.80F : switch (d) {
            case 1 -> 0.10F;
            case 2 -> 0.20F;
            case 3 -> 0.45F;
            default -> 0.50F;
        };
        amount *= 1.0F + Math.min(20.0F, armor) * 0.04F * penetration;
        double distance = this.distanceTo(victim);
        double range = getAttackRange();
        if (distance > range) amount *= Math.max(0.15F, (float) (range / distance));
        return amount;
    }

    @Override
    protected void dropNpcLoot(net.minecraft.world.damagesource.DamageSource source, int looting, boolean recentlyHit) {
        // Bounty hunter armor never drops.
        for (EquipmentSlot slot : new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            this.setItemSlot(slot, ItemStack.EMPTY);
        }
        ItemStack gun = this.getGunForDrop();
        this.dropGunItem(gun);
        if (this.ammoId != null) this.dropAmmo(64);
        if (this.random.nextFloat() < 0.10F) {
            int apples = this.random.nextInt(3);
            if (apples > 0) this.spawnAtLocation(new ItemStack(Items.GOLDEN_APPLE, apples));
        }
        if (this.random.nextFloat() < 0.30F) {
            Item[] food = {Items.BREAD, Items.BAKED_POTATO, Items.COOKED_BEEF, Items.COOKED_PORKCHOP};
            Item chosen = food[this.random.nextInt(food.length)];
            this.spawnAtLocation(new ItemStack(chosen, 1 + this.random.nextInt(6)));
        }
    }
}
