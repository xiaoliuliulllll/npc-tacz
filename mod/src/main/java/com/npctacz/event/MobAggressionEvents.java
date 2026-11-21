package com.npctacz.event;

import com.npctacz.ModRegistry;
import com.npctacz.entity.WastelandNpcEntity;
import com.npctacz.entity.goal.MonsterNpcTargetGoal;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * 让原版敌对生物主动攻击 NPC，并顺带诊断 NPC 的子弹实体是否真的生成。
 *
 * <p>Forge 没有"给原版怪物加仇恨"的事件，所以在实体加入世界时给每只 {@link Enemy}
 * （僵尸/骷髅/蜘蛛/苦力怕/史莱姆/恶魂/幻翼……）注入一次
 * {@link MonsterNpcTargetGoal}。判断是否已经注入用的是"目标选择器里有没有这个
 * Goal"，而不是 NBT 标记：区块重载后怪物会重新构造，NBT 标记会让它在重建后
 * 拿不到 Goal。</p>
 *
 * <p>不注入到我们的 NPC 自己（WastelandNpcEntity 不是 Enemy）。</p>
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MobAggressionEvents {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static int npcBulletLogs;

    private MobAggressionEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (logNpcBullet(entity)) {
            return;
        }
        if (!(entity instanceof Mob mob) || !(mob instanceof Enemy)) {
            return;
        }
        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof MonsterNpcTargetGoal) {
                return;
            }
        }
        mob.targetSelector.addGoal(0, new MonsterNpcTargetGoal(mob));
    }

    /**
     * 诊断用：NPC 开枪时 TACZ 会生成 kinetic bullet 实体。
     * 这里记录前 5 发，确认"子弹实体真的生成了"。
     */
    private static boolean logNpcBullet(Entity entity) {
        if (!(entity instanceof Projectile projectile)) {
            return false;
        }
        if (!(projectile.getOwner() instanceof WastelandNpcEntity npc)) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (id == null || !"tacz".equals(id.getNamespace())) {
            return false;
        }
        if (npcBulletLogs < 5) {
            npcBulletLogs++;
            LOGGER.info("[npc-tacz] bullet entity spawned: type={} owner={} at={}",
                id, npc.getFaction(), entity.blockPosition());
        }
        return true;
    }
}
