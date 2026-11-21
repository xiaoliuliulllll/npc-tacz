package com.npctacz.entity.ai;

import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * 枪械/配件筛选规则：NPC 永不使用爆炸类枪械与爆炸类配件。
 *
 * <p>两条腿走路：</p>
 * <ol>
 *   <li>资源 id 关键字黑名单（覆盖各种枪包命名习惯）；</li>
 *   <li>读取 TACZ 的枪械数据里 {@code BulletData.getExplosionData() != null} 的精确判断，
 *       不依赖 id 命名，任何爆炸弹丸的枪都会被过滤。</li>
 * </ol>
 */
public final class NpcGunRules {

    /** 枪械 id 里的爆炸关键字。 */
    /** 完整单词/片段关键字（用 id 的 token 匹配，避免 glock 被 "gl" 误伤）。 */
    private static final String[] EXPLOSIVE_GUN_KEYWORDS = {
        "explosive", "explosion", "grenade", "rpg", "rocket", "launcher",
        "c4", "tnt", "mortar", "missile", "bomb", "warhead",
        "40mm", "m320", "m79", "gp25", "gl", "he"
    };

    /** 这些是唯一允许用子串匹配的较长关键字。 */
    private static final String[] EXPLOSIVE_GUN_CONTAINS = {
        "explosive", "explosion", "grenade", "launcher", "warhead", "40mm",
        "m320", "m79", "gp25"
    };

    /** 配件 id 里的爆炸关键字（弹药改装/爆炸弹头等）。 */
    private static final String[] EXPLOSIVE_ATTACHMENT_KEYWORDS = {
        "explosive", "explosion", "grenade", "rpg", "rocket", "launcher",
        "c4", "tnt", "mortar", "missile", "bomb", "warhead", "he", "he_",
        "_he", "incendiary", "燃烧", "爆炸"
    };

    private NpcGunRules() {
    }

    public static boolean isExplosiveId(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (String keyword : EXPLOSIVE_GUN_CONTAINS) {
            if (path.contains(keyword)) {
                return true;
            }
        }
        for (String token : path.split("[^a-z0-9]+")) {
            for (String keyword : EXPLOSIVE_GUN_KEYWORDS) {
                if (token.equals(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 用 TACZ 枪械数据判断是否爆炸弹丸（与命名无关）。 */
    public static boolean isExplosiveGun(CommonGunIndex index) {
        if (index == null || index.getBulletData() == null) {
            return false;
        }
        try {
            var explosion = index.getBulletData().getExplosionData();
            // 很多枪包会写 explosion 配置但 explode=false，不能算爆炸枪
            return explosion != null && explosion.isExplode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 用 TACZ 配件数据判断是否带爆炸效果（不依赖 id 命名）。 */
    public static boolean isExplosiveAttachment(com.tacz.guns.resource.index.CommonAttachmentIndex index) {
        if (index == null || index.getData() == null) {
            return false;
        }
        try {
            var modifier = index.getData().getModifier();
            if (modifier == null) {
                return false;
            }
            for (String key : modifier.keySet()) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.equals("explosion") || lower.equals("explosive")
                    || lower.contains("explosion") || lower.contains("explosive")
                    || lower.contains("grenade")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /** id 或数据任一命中爆炸属性都算爆炸配件。 */
    public static boolean isExplosiveAttachment(ResourceLocation id) {
        if (isExplosiveAttachmentId(id)) {
            return true;
        }
        if (id == null) {
            return false;
        }
        try {
            for (var entry : com.tacz.guns.api.TimelessAPI.getAllCommonAttachmentIndex()) {
                if (entry.getKey().equals(id)) {
                    return isExplosiveAttachment(entry.getValue());
                }
            }
        } catch (Throwable ignored) {
            // 资源未加载时忽略
        }
        return false;
    }

    /** 逆天配件：伤害倍率 > 3，或其它非良性属性倍率 > 1.6（+60%）。 */
    /**
     * 非步枪武器的 NPC 射速上限：狙击枪不再连发，霰弹/手枪/SMG/MG 也按类型限速。
     * rifle 类型保持原样。
     */
    private static final java.util.concurrent.ConcurrentHashMap<ResourceLocation, String> GUN_TYPE_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 枪包/数据重载后清空按 id 缓存的枪型，避免旧类型继续限制射速。 */
    public static void clearCaches() {
        GUN_TYPE_CACHE.clear();
    }

    /** 只按枪 id 查一次类型并缓存，避免 NPC 每发子弹都走 TimelessAPI 索引查询。 */
    /** 是否为狙击枪（使用同一份枪型缓存）。 */
    public static boolean isSniper(ResourceLocation gunId) {
        if (gunId == null) {
            return false;
        }
        String type = GUN_TYPE_CACHE.get(gunId);
        if (type == null) {
            try {
                var index = com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gunId).orElse(null);
                type = index == null || index.getType() == null ? "" : index.getType();
                if (!type.isEmpty()) {
                    GUN_TYPE_CACHE.put(gunId, type);
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return "sniper".equalsIgnoreCase(type);
    }

    public static int cappedNpcRpm(ResourceLocation gunId, int rawRpm) {
        if (gunId == null) {
            return Math.max(1, rawRpm);
        }
        String type = GUN_TYPE_CACHE.get(gunId);
        if (type == null) {
            try {
                var index = com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gunId).orElse(null);
                type = index == null || index.getType() == null ? "" : index.getType();
                if (!type.isEmpty()) {
                    GUN_TYPE_CACHE.put(gunId, type);
                }
            } catch (Throwable ignored) {
                return Math.max(1, rawRpm);
            }
        }
        return cappedNpcRpmType(type, rawRpm);
    }

    public static int cappedNpcRpm(com.tacz.guns.resource.index.CommonGunIndex index, int rawRpm) {
        return cappedNpcRpmType(index == null ? "" : index.getType(), rawRpm);
    }

    private static int cappedNpcRpmType(String rawType, int rawRpm) {
        int rpm = Math.max(1, rawRpm);
        if (rawType == null || rawType.isEmpty()) {
            return rpm;
        }
        String type = rawType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "sniper" -> Math.min(rpm, 45);
            case "shotgun" -> Math.min(rpm, 100);
            case "pistol" -> Math.min(rpm, 320);
            case "smg" -> Math.min(rpm, 750);
            case "mg", "machine_gun", "hmg" -> Math.min(rpm, 500);
            case "rifle" -> rpm;
            default -> Math.min(rpm, 400);
        };
    }

    public static boolean isOverpoweredAttachment(com.tacz.guns.resource.index.CommonAttachmentIndex index) {
        if (index == null || index.getData() == null) {
            return false;
        }
        try {
            var modifier = index.getData().getModifier();
            if (modifier == null) {
                return false;
            }
            for (var entry : modifier.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                if (key.equals("explosion") || key.equals("explosive") || key.contains("grenade")) {
                    return true;
                }
                if (isBenignModifier(key)) {
                    continue;
                }
                Object value = entry.getValue() == null ? null : entry.getValue().getValue();
                if (modifierViolates(value, key)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /** 配件 id 对应的数据，做逆天属性判定。 */
    public static boolean isOverpoweredAttachment(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        try {
            var index = com.tacz.guns.api.TimelessAPI.getCommonAttachmentIndex(id).orElse(null);
            return isOverpoweredAttachment(index);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBenignModifier(String key) {
        return key.contains("weight") || key.contains("silence") || key.contains("smoke")
            || key.contains("flash") || key.contains("extended_mag_level")
            || key.contains("melee") || key.contains("ads") || key.contains("sprint")
            || key.contains("draw") || key.contains("reload");
    }

    private static boolean modifierViolates(Object value, String key) {
        if (value == null) {
            return false;
        }
        if (value instanceof com.tacz.guns.resource.pojo.data.attachment.Modifier modifier) {
            boolean damage = key.contains("damage") || key.contains("head_shot") || key.contains("headshot");
            if (damage) {
                return modifier.getMultiplier() > 3.0D || modifier.getPercent() > 3.0D;
            }
            return modifier.getMultiplier() > 1.6D || modifier.getPercent() > 0.6D;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            for (Object item : map.values()) {
                if (modifierViolates(item, key)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (modifierViolates(item, key)) {
                    return true;
                }
            }
            return false;
        }
        // fastutil Pair（recoil 的 pitch/yaw 用 Pair 包两个 Modifier）
        try {
            Class<?> type = value.getClass();
            if (type.getName().startsWith("it.unimi.dsi.fastutil.Pair")) {
                var left = type.getMethod("left").invoke(value);
                var right = type.getMethod("right").invoke(value);
                return modifierViolates(left, key) || modifierViolates(right, key);
            }
        } catch (Throwable ignored) {
            // 不是 Pair
        }
        return false;
    }

    /**
     * NPC 掉落的枪统一改成“能全自动就全自动，否则半自动/点射”；
     * 玩家捡到后不需要先按 G 切换。
     */
    public static void applyBestFireMode(net.minecraft.world.item.ItemStack stack) {
        try {
            var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
            if (gun == null) {
                return;
            }
            var index = com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElse(null);
            if (index == null || index.getGunData() == null) {
                return;
            }
            java.util.List<com.tacz.guns.api.item.gun.FireMode> modes = index.getGunData().getFireModeSet();
            if (modes == null || modes.isEmpty()) {
                return;
            }
            com.tacz.guns.api.item.gun.FireMode target = null;
            for (var mode : modes) {
                if (mode == com.tacz.guns.api.item.gun.FireMode.AUTO) {
                    target = mode;
                    break;
                }
            }
            if (target == null) {
                for (var mode : modes) {
                    if (mode == com.tacz.guns.api.item.gun.FireMode.SEMI) {
                        target = mode;
                        break;
                    }
                }
            }
            if (target == null) {
                target = modes.get(0);
            }
            gun.setFireMode(stack, target);
        } catch (Throwable ignored) {
            // 枪械数据不完整时保留原 fire mode
        }
    }

    public static boolean isExplosiveAttachmentId(ResourceLocation id) {
        if (id == null) {
            return false;
        }
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (String token : path.split("[^a-z0-9]+")) {
            for (String keyword : EXPLOSIVE_ATTACHMENT_KEYWORDS) {
                if (token.equals(keyword)) {
                    return true;
                }
            }
        }
        // 只对较长关键字做子串匹配，he 这类短词严格按 token
        return path.contains("explosive") || path.contains("explosion")
            || path.contains("grenade") || path.contains("launcher")
            || path.contains("warhead") || path.contains("incendiary");
    }
}
