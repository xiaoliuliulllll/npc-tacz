package com.npctacz.entity;

public enum NpcFaction {
    SOLDIER,
    BANDIT,
    REBEL,
    BOUNTY_HUNTER,
    MERCENARY;

    public boolean isHostileTo(NpcFaction other) {
        if (other == null || other == this) {
            return false;
        }
        if (this == BOUNTY_HUNTER || other == BOUNTY_HUNTER
            || this == MERCENARY || other == MERCENARY) {
            return false;
        }
        if (this == REBEL || other == REBEL) {
            return true;
        }
        return (this == SOLDIER && other == BANDIT)
            || (this == BANDIT && other == SOLDIER);
    }
}
