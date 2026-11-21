package com.npctacz.entity.goal;

import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * 给原版敌对怪（Zombie / Skeleton / Spider / Creeper ...）注入的目标选择。
 *
 * <p>原版怪物不会把自定义 NPC 当成仇恨对象；这个 Goal 会扫描周围一定范围内的
 * NPC，把最近的一只设为攻击目标，之后怪物自己的近战/远程 AI 就会正常接管。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>怪物已经有活着的目标时（比如正在追玩家）不抢目标，避免抢仇恨；</li>
 *   <li>赏金猎人是全中立的，怪物不会主动攻击它；</li>
 *   <li>要求怪物能看到 NPC，避免隔着墙锁定。</li>
 * </ul>
 */
public class MonsterNpcTargetGoal extends TargetGoal {

    private static final double SEARCH_RADIUS = 40.0D;
    private static final double GIVE_UP_RADIUS = 64.0D;
    private static final int SCAN_INTERVAL_TICKS = 40;

    private final Mob monster;
    private int scanCooldown;
    @Nullable
    private WastelandNpcEntity chosen;

    public MonsterNpcTargetGoal(Mob monster) {
        super(monster, false);
        this.monster = monster;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return false;
        }
        this.scanCooldown = SCAN_INTERVAL_TICKS;

        LivingEntity current = this.monster.getTarget();
        if (current != null && current.isAlive()) {
            return false;
        }
        WastelandNpcEntity found = this.findTargetNpc();
        if (found == null) {
            return false;
        }
        this.chosen = found;
        return true;
    }

    @Override
    public void start() {
        this.monster.setTarget(this.chosen);
        this.monster.setLastHurtByMob(null);
        super.start();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.monster.getTarget();
        if (!(target instanceof WastelandNpcEntity npc)) {
            return false;
        }
        return npc.isAlive()
            && npc.acceptsMobAggression()
            && this.monster.distanceToSqr(npc) <= GIVE_UP_RADIUS * GIVE_UP_RADIUS;
    }

    @Nullable
    private WastelandNpcEntity findTargetNpc() {
        AABB box = this.monster.getBoundingBox().inflate(SEARCH_RADIUS, 16.0D, SEARCH_RADIUS);
        WastelandNpcEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (WastelandNpcEntity npc : this.monster.level().getEntitiesOfClass(WastelandNpcEntity.class, box)) {
            if (!npc.isAlive() || !npc.acceptsMobAggression()) {
                continue;
            }
            if (!this.monster.canAttack(npc)) {
                continue;
            }
            if (!this.monster.hasLineOfSight(npc)) {
                continue;
            }
            double distance = this.monster.distanceToSqr(npc);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = npc;
            }
        }
        return best;
    }
}
