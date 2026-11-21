package com.npctacz.entity;

import com.npctacz.data.NpcDifficultyData;
import com.npctacz.data.SoldierHuntData;
import com.npctacz.entity.ai.NpcGunRules;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.UUID;

/**
 * Soldier with per-difficulty values. The AI, pathfinding, shelter and breach
 * code stays in WastelandNpcEntity; only this NPC's numbers are changed here.
 */
public class SoldierNpc extends WastelandNpcEntity {
    public SoldierNpc(EntityType<? extends SoldierNpc> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 26.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.09D)
            .add(Attributes.ATTACK_DAMAGE, 2.0D)
            .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    public NpcFaction getFaction() {
        return NpcFaction.SOLDIER;
    }

    @javax.annotation.Nullable
    private UUID huntPlayer;
    private BlockPos huntPos = BlockPos.ZERO;
    private long huntAssignedAt;

    private int difficulty() {
        if (this.level() instanceof ServerLevel serverLevel) {
            return NpcDifficultyData.get(serverLevel).getDifficulty();
        }
        return 2;
    }

    // ------------------------------------------------------------------ 三日追杀

    /** Called at noon for the randomly selected 40% of soldiers. */
    public void assignHunt(ServerPlayer target, BlockPos targetPos, long gameTime) {
        this.huntPlayer = target.getUUID();
        this.huntPos = targetPos == null ? target.blockPosition() : targetPos.immutable();
        this.huntAssignedAt = gameTime;
        this.setTarget(target);
    }

    @javax.annotation.Nullable
    public UUID getHuntPlayer() {
        return this.huntPlayer;
    }

    public BlockPos getHuntPos() {
        return this.huntPos;
    }

    private boolean isHuntTarget(Player player) {
        if (this.huntPlayer == null || player == null || !this.huntPlayer.equals(player.getUUID())) {
            return false;
        }
        return this.level() instanceof ServerLevel serverLevel
            && SoldierHuntData.get(serverLevel).isHunting(player, serverLevel.getGameTime());
    }

    /** Soldiers are neutral to players unless this player is their current hunt target. */
    @Override
    public boolean isHostileTo(net.minecraft.world.entity.LivingEntity other) {
        if (other instanceof Player player) {
            boolean creativeAllowed = this.allowsCreativePlayers();
            boolean spectatorAllowed = this.allowsSpectatorPlayers();
            if (!creativeAllowed && player.isCreative()) {
                return false;
            }
            if (!spectatorAllowed && player.isSpectator()) {
                return false;
            }
            if (!this.isWithinPlayerViewDistance(player)) {
                return false;
            }
            // 配置允许攻击创造/旁观玩家时，士兵也直接敌对（否则只有被打了才反击）
            if ((player.isCreative() && creativeAllowed)
                || (player.isSpectator() && spectatorAllowed)) {
                return true;
            }
            // 管理员界面：士兵是否主动与玩家敌对；玩家先动手时后面的 lastHurtByMob 仍会反制
            if (this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                && com.npctacz.data.NpcDifficultyData.get(serverLevel).isSoldierHostilePlayers()) {
                return true;
            }
            if (this.getLastHurtByMob() == player) {
                return true;
            }
            return this.isHuntTarget(player);
        }
        return super.isHostileTo(other);
    }

    /** Hunters need a larger awareness radius to reach the point captured at noon. */
    @Override
    public double getAwarenessRange() {
        return this.huntPlayer == null ? super.getAwarenessRange() : Math.max(256.0D, super.getAwarenessRange());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || this.huntPlayer == null) {
            return;
        }
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        SoldierHuntData data = SoldierHuntData.get(serverLevel);
        if (!data.isHunting(this.huntPlayer, serverLevel.getGameTime())) {
            this.huntPlayer = null;
            if (this.getTarget() instanceof Player) {
                this.setTarget(null);
            }
            return;
        }
        ServerPlayer target = serverLevel.getServer().getPlayerList().getPlayer(this.huntPlayer);
        if (target == null || target.level() != this.level()) {
            return;
        }
        if (this.getTarget() == null && this.distanceToSqr(target) <= 256.0D * 256.0D) {
            this.setTarget(target);
        }
        // Long chase: the navigator clamps far targets to a local point, so this
        // stays expensive only once every 20 ticks instead of every tick.
        if (this.distanceToSqr(target) > this.getAttackRange() * this.getAttackRange()
            && this.tickCount % 20 == 0) {
            this.navigator().moveTo(target, 1.1D);
        }
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.huntPlayer != null) tag.putUUID("HuntPlayer", this.huntPlayer);
        tag.putLong("HuntPos", this.huntPos.asLong());
        tag.putLong("HuntAssignedAt", this.huntAssignedAt);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("HuntPlayer")) this.huntPlayer = tag.getUUID("HuntPlayer");
        if (tag.contains("HuntPos")) this.huntPos = BlockPos.of(tag.getLong("HuntPos"));
        this.huntAssignedAt = tag.getLong("HuntAssignedAt");
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
        double health = switch (d) {
            case 1 -> 18.0D;
            case 3 -> 32.0D;
            case 4 -> 40.0D;
            default -> 26.0D;
        };
        var maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(health);
            this.setHealth((float) health);
        }
        return data;
    }

    @Override
    public int getInitialAmmo() {
        return switch (difficulty()) {
            case 1 -> 60;
            case 3 -> 180;
            case 4 -> 300;
            default -> 120;
        };
    }

    @Override
    public int getMaxAmmo() {
        return getInitialAmmo();
    }

    @Override
    public int getRefillAmount() {
        return switch (difficulty()) {
            case 1 -> 20;
            case 3 -> 60;
            case 4 -> 90;
            default -> 40;
        };
    }

    @Override
    public int getNpcRpm(IGun gun, ItemStack stack) {
        int base = Math.max(1, gun.getRPM(stack));
        return switch (difficulty()) {
            case 1 -> Math.max(100, base - 300);
            case 2 -> base < 200 ? base : Math.max(200, base - 100);
            case 3 -> base + 10;
            case 4 -> base + 60;
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
                case 3 -> 128.0D;
                default -> 156.0D;
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
        double base = Math.max(28.0D, getAttackRange());
        return this.huntPlayer == null ? base : Math.max(256.0D, base);
    }

    /** Keep the ranged combat goal short-ranged for hunters; they walk with the navigator. */
    @Override
    public double getEngageRange() {
        if (this.huntPlayer == null) {
            return super.getEngageRange();
        }
        return Math.max(32.0D, getAttackRange() * 1.25D);
    }

    @Override
    public String chooseCategory() {
        int roll = this.random.nextInt(100);
        int d = difficulty();
        if (d <= 2) {
            if (roll < 60) return "rifle";
            if (roll < 90) return "smg";
            return "sniper";
        }
        if (roll < 60) return "rifle";
        if (roll < 70) return "smg";
        if (roll < 90) return "sniper";
        return "mg";
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
            float maxDamage = sniperCategory ? 20.0F + 10.0F * (d - 1) : switch (d) {
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
        int protMax = switch (d) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 2;
            default -> 3;
        };
        int protMin = d >= 4 ? 1 : 0;
        int projMin = d >= 3 ? 1 : 0;
        int projMax = switch (d) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 2;
            default -> 3;
        };
        for (EquipmentSlot slot : new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            ItemStack stack = new ItemStack(armorFor(slot, d));
            int prot = protMin + this.random.nextInt(protMax - protMin + 1);
            int proj = projMin + this.random.nextInt(projMax - projMin + 1);
            if (prot > 0) stack.enchant(Enchantments.ALL_DAMAGE_PROTECTION, prot);
            if (proj > 0) stack.enchant(Enchantments.PROJECTILE_PROTECTION, proj);
            this.setItemSlot(slot, stack);
        }
    }

    private Item armorFor(EquipmentSlot slot, int d) {
        String type = switch (slot) {
            case HEAD -> "helmet";
            case CHEST -> "chestplate";
            case LEGS -> "leggings";
            default -> "boots";
        };
        int roll = this.random.nextInt(100);
        String material;
        if (d == 1) {
            material = roll < 50 ? "leather" : "chainmail";
        } else if (d == 2) {
            material = roll < 50 ? "iron" : "chainmail";
        } else if (d == 3) {
            if (roll < 10) material = "diamond";
            else material = roll < 55 ? "iron" : "gold";
        } else {
            if (roll < 10) material = "netherite";
            else if (roll < 60) material = "diamond";
            else material = "iron";
        }
        return ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft", material + "_" + type));
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
     * Called only from the soldier damage event. The AI still fires the gun;
     * this only replaces the final damage number and armor penetration.
     */
    public float modifySoldierDamage(LivingEntity victim, float rawGunDamage) {
        int d = difficulty();
        boolean sniper = isSniper();
        float amount;
        if (victim instanceof Player) {
            amount = sniper
                ? (5.0F + 6.0F * (d - 1)) + this.random.nextFloat() * 5.0F
                : switch (d) {
                    case 1 -> 0.5F + this.random.nextFloat();
                    case 2 -> 1.5F + this.random.nextFloat() * 1.5F;
                    case 3 -> 3.5F + this.random.nextFloat() * 1.5F;
                    default -> 6.5F + this.random.nextFloat() * 3.0F;
                };
        } else {
            float factor = switch (d) {
                case 1 -> 0.1F;
                case 2 -> 0.45F;
                case 3 -> 0.6F;
                default -> 1.3F;
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
        double range = getAttackRange();
        if (distance > range) {
            amount *= Math.max(0.15F, (float) (range / distance));
        }
        return amount;
    }

    @Override
    protected void dropNpcLoot(net.minecraft.world.damagesource.DamageSource source, int looting, boolean recentlyHit) {
        // Soldier armor never drops.
        for (EquipmentSlot slot : new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            this.setItemSlot(slot, ItemStack.EMPTY);
        }

        ItemStack gun = this.getGunForDrop();
        this.dropGunItem(gun);
        if (this.ammoId != null) {
            this.dropAmmo(this.random.nextInt(65));
            if (this.random.nextFloat() < 0.60F) {
                this.dropAmmo(this.random.nextInt(65));
            }
            if (this.random.nextFloat() < 0.30F) {
                this.dropAmmo(64);
            }
        }
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
