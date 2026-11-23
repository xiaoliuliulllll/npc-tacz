package com.npctacz.entity;

public enum NpcFaction {
    SOLDIER,
    BANDIT,
    REBEL,
    BOUNTY_HUNTER,
    MERCENARY;

    public boolean isHostileTo(NpcFaction other) {
        // 各阵营之间默认敌对；同阵营不互相攻击。具体目标优先级由 AI 决定。
        return other != null && other != this;
    }
}
