package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.config.NpcGlobalConfig;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.lang.reflect.Field;

/**
 * 让 NPC 发射的 TACZ 子弹显示曳光弹道。
 *
 * <p>TACZ 默认只有 tracer 弹药才画弹道线，NPC 拿普通弹时看起来“没有弹道”。
 * 这里只对 owner 是 WastelandNpcEntity 的 KineticBullet 打开 isTracerAmmo，
 * 玩家子弹保持 TACZ 原版行为。</p>
 *
 * <p>反射只改一个布尔字段，不复制/修改 TACZ 资源；可用 tacz-npc-modif 的
 * {@code npc_bullet_tracer} 关闭。</p>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcBulletTracerEvents {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static Field tracerField;
    private static boolean reflectionFailed;
    private static int logCount;

    private NpcBulletTracerEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!NpcGlobalConfig.getBoolean("npc_bullet_tracer", true)) {
            return;
        }
        Entity entity = event.getEntity();
        if (!entity.getClass().getName().equals("com.tacz.guns.entity.EntityKineticBullet")) {
            return;
        }
        if (!(entity instanceof net.minecraft.world.entity.projectile.Projectile projectile)) {
            return;
        }
        Entity owner = projectile.getOwner();
        if (!(owner instanceof WastelandNpcEntity)) {
            return;
        }
        try {
            if (tracerField == null) {
                tracerField = entity.getClass().getDeclaredField("isTracerAmmo");
                tracerField.setAccessible(true);
            }
            tracerField.setBoolean(entity, true);
            if (logCount < 5) {
                logCount++;
                LOGGER.info("[npc-tacz] npc bullet tracer enabled for {}", owner.getName().getString());
            }
        } catch (Throwable throwable) {
            if (!reflectionFailed) {
                reflectionFailed = true;
                LOGGER.warn("[npc-tacz] failed to enable npc bullet tracer", throwable);
            }
        }
    }
}
