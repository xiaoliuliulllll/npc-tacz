package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.entity.WastelandNpcEntity;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 枪声听觉。
 *
 * <p>监听 TACZ 的 {@link GunShootEvent}（任何枪械开火都会触发），按枪械类型决定
 * 声音能传多远，然后让范围内的敌对 NPC 朝声源位置搜过去。</p>
 *
 * <p>传播距离：</p>
 * <ul>
 *   <li>手枪 / 冲锋枪：32 格</li>
 *   <li>步枪 / 机枪：96 格</li>
 *   <li>狙击枪：128 格</li>
 *   <li>其他（霰弹枪、重型等未指定）：64 格</li>
 * </ul>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>只对"和自己敌对"的开火者有反应，同阵营开枪不会互相跑去围观；</li>
 *   <li>赏金猎人对怪物中立，所以只会被玩家的枪声吸引；</li>
 *   <li>每 10 tick 只处理一次同一开火者的枪声，连发时不会每颗子弹都全场扫描。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GunSoundEvents {

    private static final int RANGE_PISTOL_SMG = 32;
    private static final int RANGE_RIFLE_MG = 96;
    private static final int RANGE_SNIPER = 128;
    private static final int RANGE_OTHER = 64;

    /** 听到枪声后保持"朝声源搜索"状态的时长（tick）。 */
    private static final int SOUND_INTEREST_TICKS = 300;

    /** 同一开火者的枪声处理间隔（tick），避免全自动武器每发都做一次全场扫描。 */
    private static final int SAME_SHOOTER_INTERVAL = 10;

    private static final Map<UUID, Long> LAST_PROCESSED = new HashMap<>();

    private GunSoundEvents() {
    }

    @SubscribeEvent
    public static void onGunShoot(GunShootEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        notifyGunshot(event.getShooter(), event.getGunItemStack());
    }

    /**
     * 广播一次枪声，让范围内的敌对 NPC 循声搜索。
     *
     * <p>玩家的枪由 {@link GunShootEvent} 驱动；NPC 用的是直接调枪械物品的
     * 简化射击路径，不会走 TACZ 的 IGunOperator，所以 NPC 开枪后由
     * {@code WastelandNpcEntity#shootAt} 也调用一次这个方法，让 NPC 对射同样有听觉反应。</p>
     */
    public static void notifyGunshot(LivingEntity shooter, ItemStack gunStack) {
        if (shooter == null || shooter.level().isClientSide()) {
            return;
        }
        int range = hearingRange(gunStack);
        if (range <= 0) {
            return;
        }
        long now = shooter.level().getGameTime();
        Long last = LAST_PROCESSED.get(shooter.getUUID());
        if (last != null && now - last < SAME_SHOOTER_INTERVAL) {
            return;
        }
        if (LAST_PROCESSED.size() > 512) {
            LAST_PROCESSED.clear();
        }
        LAST_PROCESSED.put(shooter.getUUID(), now);

        AABB area = shooter.getBoundingBox().inflate(range, 16.0D, range);
        double rangeSqr = (double) range * (double) range;
        for (WastelandNpcEntity npc : shooter.level().getEntitiesOfClass(WastelandNpcEntity.class, area)) {
            if (npc == shooter || !npc.isAlive()) {
                continue;
            }
            if (npc.distanceToSqr(shooter) > rangeSqr) {
                continue;
            }
            // 只被敌人/中立目标的枪声吸引，同阵营不互相围观
            if (!npc.isHostileTo(shooter)) {
                continue;
            }
            npc.onHeardGunshot(shooter.position(), SOUND_INTEREST_TICKS);
        }
    }

    /**
     * 按枪械类型返回传播距离；读不到枪械数据时用默认值。
     */
    private static int hearingRange(ItemStack gunStack) {
        IGun gun = IGun.getIGunOrNull(gunStack);
        if (gun == null) {
            return RANGE_OTHER;
        }
        ResourceLocation gunId = gun.getGunId(gunStack);
        if (gunId == null) {
            return RANGE_OTHER;
        }
        String type = TimelessAPI.getCommonGunIndex(gunId)
            .map(CommonGunIndex::getType)
            .orElse("");
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "pistol", "smg" -> RANGE_PISTOL_SMG;
            case "rifle", "mg" -> RANGE_RIFLE_MG;
            case "sniper" -> RANGE_SNIPER;
            default -> RANGE_OTHER;
        };
    }
}
