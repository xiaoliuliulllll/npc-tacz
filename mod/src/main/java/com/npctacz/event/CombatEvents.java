package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.entity.NpcFaction;
import com.npctacz.entity.WastelandNpcEntity;
import com.npctacz.entity.BanditNpc;
import com.npctacz.entity.BountyHunterNpc;
import com.npctacz.entity.MercenaryNpc;
import com.npctacz.entity.RebelNpc;
import com.npctacz.entity.SoldierNpc;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Rebel-specific damage rules. Original implementation.
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CombatEvents {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static int npcBulletHitLogs;
    private static net.minecraft.server.level.ServerLevel cachedCombatLevel;
    private static long cachedCombatTick = Long.MIN_VALUE;
    private static float cachedGlobalCombatMultiplier = 1.0F;

    /**
     * 同阵营免伤：自己人打自己人不掉血。
     * 在伤害真正结算前拦掉，避免子弹/近战/爆炸等各种来源的差异。
     */
    @SubscribeEvent
    public static void onLivingAttack(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if (!(event.getEntity() instanceof WastelandNpcEntity victim)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof WastelandNpcEntity attacker)) {
            return;
        }
        if (victim == attacker) {
            return;
        }
        if (victim.getFaction() == attacker.getFaction()
            && com.npctacz.config.NpcGlobalConfig.getBoolean("same_faction_immune", true)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        // 所有 NPC 受到近战伤害的全局倍率（直接实体 == 来源实体，排除子弹/箭/爆炸）
        if (event.getEntity() instanceof WastelandNpcEntity victimNpc
            && !victimNpc.level().isClientSide
            && event.getSource().getEntity() != null
            && event.getSource().getDirectEntity() == event.getSource().getEntity()
            && victimNpc.level() instanceof net.minecraft.server.level.ServerLevel meleeLevel) {
            double meleeMultiplier = com.npctacz.data.NpcDifficultyData.get(meleeLevel)
                .getMeleeDamageTakenMultiplier();
            if (meleeMultiplier != 1.0D) {
                event.setAmount((float) (event.getAmount() * meleeMultiplier));
            }
        }
        // 玩家用枪爆头 NPC：额外固定伤害（默认 +6.5，Alt+N 可调）
        if (event.getEntity() instanceof WastelandNpcEntity victimNpc
            && event.getSource().getEntity() instanceof Player
            && victimNpc.level() instanceof net.minecraft.server.level.ServerLevel headLevel
            && isGunHeadshot(event, victimNpc)) {
            double bonus = com.npctacz.data.NpcDifficultyData.get(headLevel)
                .getPlayerHeadshotBonus();
            if (bonus > 0.0D) {
                event.setAmount((float) Math.min(2147483647.0D, event.getAmount() + bonus));
            }
        }
        // 赏金猎人造成的所有伤害 x0.8
        if (event.getSource().getEntity() instanceof BountyHunterNpc
            && !event.getEntity().level().isClientSide) {
            event.setAmount(event.getAmount() * 0.8F);
        }
        // Mercenary owner rules: owner deals at most 1 damage, mercenary deals
        // no damage to the owner, and other players take/deal the adjusted damage.
        if (event.getEntity() instanceof MercenaryNpc mercenary) {
            Entity sourceEntity = event.getSource().getEntity();
            if (sourceEntity instanceof Player player) {
                if (mercenary.isOwner(player)) {
                    event.setAmount(Math.min(event.getAmount(), 1.0F));
                    return;
                }
                event.setAmount(event.getAmount() * 2.0F);
                return;
            }
        }
        if (event.getSource().getEntity() instanceof MercenaryNpc mercenary
            && event.getEntity() instanceof Player player) {
            if (mercenary.isOwner(player)) {
                event.setCanceled(true);
                return;
            }
            // 雇佣兵对玩家伤害 x0.75（先于后续全局倍率/封顶生效）
            event.setAmount(event.getAmount() * 0.75F);
        }
        if (!(event.getSource().getEntity() instanceof WastelandNpcEntity attacker)) {
            return;
        }
        // 管理员界面：全局 NPC 伤害倍率 × 难度伤害倍率（缓存 20 tick，避免每次受击查 SavedData）
        if (attacker.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            float globalMultiplier = globalCombatMultiplier(serverLevel);
            if (globalMultiplier != 1.0F) {
                event.setAmount(event.getAmount() * globalMultiplier);
            }
            double factionMultiplier = com.npctacz.data.NpcDifficultyData.get(serverLevel)
                .getFactionDamageMultiplier(attacker.getFaction());
            if (factionMultiplier != 1.0D) {
                event.setAmount((float) (event.getAmount() * factionMultiplier));
            }
        }
        // 同阵营 NPC 之间绝对免伤（子弹/爆炸/近战全挡）
        if (event.getEntity() instanceof WastelandNpcEntity victimNpc
            && victimNpc != attacker
            && attacker.getFaction() == victimNpc.getFaction()
            && com.npctacz.config.NpcGlobalConfig.getBoolean("same_faction_immune", true)) {
            event.setCanceled(true);
            return;
        }
        Entity direct = event.getSource().getDirectEntity();
        String directId = direct == null ? "" : String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(direct.getType()));
        if (!"tacz:bullet".equals(directId) && !"tacz:kinetic_bullet".equals(directId)) {
            return;
        }
        LivingEntity victim = event.getEntity();
        if (npcBulletHitLogs < 5) {
            npcBulletHitLogs++;
            LOGGER.info("[npc-tacz] npc bullet hit: attacker={} victim={} damage={}",
                attacker.getFaction(), victim.getName().getString(), event.getAmount());
        }
        boolean headshot = direct != null
            && direct.getY() > victim.getY() + victim.getBbHeight() * 0.8D;
        float headshotBonus = headshot ? attacker.getNpcDifficulty() : 0.0F;

        float amount = event.getAmount();
        if (attacker instanceof MercenaryNpc mercenary) {
            amount = mercenary.modifySoldierDamage(victim, amount) + headshotBonus;
            event.setAmount(Math.max(0.5F, amount));
            return;
        } else if (attacker instanceof SoldierNpc soldier) {
            amount = soldier.modifySoldierDamage(victim, amount);
        } else if (attacker instanceof BanditNpc bandit) {
            amount = bandit.modifyBanditDamage(victim, amount);
        } else if (attacker instanceof RebelNpc rebel) {
            amount = rebel.modifyRebelDamage(victim, amount);
        } else if (attacker instanceof BountyHunterNpc hunter) {
            amount = hunter.modifyBountyHunterDamage(victim, amount);
        } else if (attacker.getFaction() == NpcFaction.REBEL) {
            if (victim instanceof Player) {
                amount = 2.0F + attacker.getRandom().nextInt(4);
            } else {
                amount = amount * 0.4F + 1.0F;
            }
            float armor = victim.getArmorValue();
            amount *= 1.0F + Math.min(20.0F, armor) * 0.04F * 0.45F;
        }
        amount += headshotBonus;

        // ---- 平衡：对玩家按难度封顶（避免一枪秒杀，保留走位和掩体的意义）；
        // ----       对 NPC 稍微压制，让对射能持续几秒而不是瞬间互秒。
        if (victim instanceof Player) {
            float cap = switch (attacker.getNpcDifficulty()) {
                case 1 -> com.npctacz.config.NpcGlobalConfig.getInt("damage_cap_difficulty1", 6);
                case 2 -> com.npctacz.config.NpcGlobalConfig.getInt("damage_cap_difficulty2", 9);
                case 3 -> com.npctacz.config.NpcGlobalConfig.getInt("damage_cap_difficulty3", 12);
                default -> com.npctacz.config.NpcGlobalConfig.getInt("damage_cap_difficulty4", 15);
            };
            amount *= (float) com.npctacz.config.NpcGlobalConfig.getDouble("combat_damage_multiplier", 1.0D);
            float armorReduction = Math.min(0.55F, victim.getArmorValue() * 0.03F);
            amount = Math.min(amount, cap) * (1.0F - armorReduction);
        } else if (victim instanceof WastelandNpcEntity) {
            amount = Math.min(amount, 14.0F) * 0.7F;
        } else {
            amount = Math.min(amount, 22.0F);
        }
        event.setAmount(Math.max(0.5F, amount));

        // 命中玩家：加强版视角/准星抖动（伤害越高抖得越明显，可用 hitKickStrength 调）
        if (victim instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            float strength = (float) com.npctacz.config.NpcGlobalConfig.getDouble(
                "camera_kick_strength",
                com.npctacz.config.NpcConfig.VALUES.hitKickStrength.get().floatValue());
            if (strength > 0.0F) {
                float damageScale = Math.max(0.6F, Math.min(1.6F, event.getAmount() / 10.0F));
                float yawKick = (attacker.getRandom().nextFloat() * 2.0F - 1.0F) * 1.6F
                    * strength * damageScale;
                float pitchKick = -(2.8F + attacker.getRandom().nextFloat() * 1.8F)
                    * strength * damageScale;
                com.npctacz.network.NpcNetwork.sendHitKick(serverPlayer, yawKick, pitchKick);
            }
        }
    }

    private static boolean isGunHeadshot(LivingHurtEvent event, WastelandNpcEntity victim) {
        var direct = event.getSource().getDirectEntity();
        if (direct == null) {
            return false;
        }
        String id = String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(direct.getType()));
        if (!"tacz:bullet".equals(id) && !"tacz:kinetic_bullet".equals(id)) {
            return false;
        }
        return direct.getY() > victim.getY() + victim.getBbHeight() * 0.8D;
    }

    private static float globalCombatMultiplier(net.minecraft.server.level.ServerLevel level) {
        long now = level.getGameTime();
        if (level != cachedCombatLevel || cachedCombatTick == Long.MIN_VALUE
            || now < cachedCombatTick || now - cachedCombatTick > 20L) {
            cachedCombatLevel = level;
            var data = com.npctacz.data.NpcDifficultyData.get(level);
            cachedGlobalCombatMultiplier = (float) (data.getGlobalDamageMultiplier()
                * data.damageMultiplier());
            cachedCombatTick = now;
        }
        return cachedGlobalCombatMultiplier;
    }

    private CombatEvents() {
    }
}
