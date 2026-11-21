package com.npctacz.entity;

import net.minecraft.util.RandomSource;

import java.util.List;

/** 给 NPC 生成像“人”的随机称呼，而不是所有单位都叫同一个阵营名。 */
public final class NpcNames {

    private static final List<String> SURNAMES = List.of(
        "陈", "林", "赵", "周", "吴", "徐", "孙", "马", "朱", "胡",
        "郭", "何", "高", "罗", "郑", "梁", "谢", "宋", "唐", "许"
    );
    private static final List<String> GIVEN = List.of(
        "锋", "磊", "强", "军", "涛", "斌", "宇", "浩", "凯", "鹏",
        "晨", "峰", "杰", "瑞", "宁", "川", "野", "铮", "远", "舟"
    );
    private static final List<String> BANDIT_NICKS = List.of(
        "疤脸", "独眼", "老鸦", "灰狗", "钉子", "秃鹫", "瘸狼", "黑手",
        "疯杰克", "瘦猴", "铁牙", "沙狐"
    );
    private static final List<String> REBEL_CODES = List.of(
        "赤星", "白鸦", "游隼", "北风", "铁砧", "长夜", "残月", "荒原"
    );
    private static final List<String> MERCS = List.of(
        "阿列克谢", "马库斯", "维克多", "罗兰", "伊万", "卡尔", "雷恩", "诺曼"
    );
    private static final List<String> BOUNTY = List.of(
        "猎手", "赏金客", "灰帽", "冷枪", "无名氏", "收账人", "游侠", "老猎人"
    );

    private NpcNames() {
    }

    public static String randomName(RandomSource random, NpcFaction faction) {
        return switch (faction) {
            case SOLDIER -> "§b士兵 §f" + pick(random, SURNAMES) + pick(random, GIVEN);
            case BANDIT -> "§c劫匪 §f" + pick(random, BANDIT_NICKS);
            case REBEL -> "§e叛军 §f" + pick(random, REBEL_CODES);
            case MERCENARY -> "§a雇佣兵 §f" + pick(random, MERCS);
            case BOUNTY_HUNTER -> "§6赏金猎人 §f" + pick(random, BOUNTY);
        };
    }

    /** 上一版随机名的前缀，用于兼容清理；不改用户自己起的名字。 */
    public static boolean looksGenerated(String name) {
        return name != null && (name.startsWith("§b士兵 §f") || name.startsWith("§c劫匪 §f")
            || name.startsWith("§e叛军 §f") || name.startsWith("§a雇佣兵 §f")
            || name.startsWith("§6赏金猎人 §f"));
    }

    private static String pick(RandomSource random, List<String> values) {
        return values.get(random.nextInt(values.size()));
    }
}
