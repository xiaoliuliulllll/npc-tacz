package com.npctacz;

import com.npctacz.entity.BanditNpc;
import com.npctacz.entity.BountyHunterNpc;
import com.npctacz.entity.MercenaryNpc;
import com.npctacz.entity.RebelNpc;
import com.npctacz.entity.SoldierNpc;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModRegistry {
    public static final String MOD_ID = "npc_tacz";

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);

    public static final DeferredRegister<Item> SPAWN_ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, "tacznpc");

    public static final RegistryObject<EntityType<SoldierNpc>> SOLDIER =
        ENTITY_TYPES.register("soldier", () -> EntityType.Builder
            .of(SoldierNpc::new, MobCategory.MONSTER)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(64)
            .build(new ResourceLocation(MOD_ID, "soldier").toString()));

    public static final RegistryObject<EntityType<BanditNpc>> BANDIT =
        ENTITY_TYPES.register("bandit", () -> EntityType.Builder
            .of(BanditNpc::new, MobCategory.MONSTER)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(64)
            .build(new ResourceLocation(MOD_ID, "bandit").toString()));

    public static final RegistryObject<EntityType<RebelNpc>> REBEL =
        ENTITY_TYPES.register("rebel", () -> EntityType.Builder
            .of(RebelNpc::new, MobCategory.MONSTER)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(64)
            .build(new ResourceLocation(MOD_ID, "rebel").toString()));

    public static final RegistryObject<EntityType<BountyHunterNpc>> BOUNTY_HUNTER =
        ENTITY_TYPES.register("bounty_hunter", () -> EntityType.Builder
            .of(BountyHunterNpc::new, MobCategory.MONSTER)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(64)
            .build(new ResourceLocation(MOD_ID, "bounty_hunter").toString()));

    public static final RegistryObject<EntityType<MercenaryNpc>> MERCENARY =
        ENTITY_TYPES.register("mercenary", () -> EntityType.Builder
            .of(MercenaryNpc::new, MobCategory.MONSTER)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(64)
            .build(new ResourceLocation(MOD_ID, "mercenary").toString()));


    public static final RegistryObject<ForgeSpawnEggItem> SOLDIER_ITEM =
        SPAWN_ITEMS.register("soldier", () -> new ForgeSpawnEggItem(
            SOLDIER, 0x5B6B4A, 0xC7B27A, new Item.Properties()));

    public static final RegistryObject<ForgeSpawnEggItem> BANDIT_ITEM =
        SPAWN_ITEMS.register("bandit", () -> new ForgeSpawnEggItem(
            BANDIT, 0x4A3B2A, 0xA87B4A, new Item.Properties()));

    public static final RegistryObject<ForgeSpawnEggItem> REBEL_ITEM =
        SPAWN_ITEMS.register("rebel", () -> new ForgeSpawnEggItem(
            REBEL, 0x3A2A2A, 0xB03A2A, new Item.Properties()));

    public static final RegistryObject<ForgeSpawnEggItem> BOUNTY_HUNTER_ITEM =
        SPAWN_ITEMS.register("bounty_hunter", () -> new ForgeSpawnEggItem(
            BOUNTY_HUNTER, 0x2A2A2A, 0xD4AF37, new Item.Properties()));


    public static final RegistryObject<ForgeSpawnEggItem> MERCENARY_ITEM =
        SPAWN_ITEMS.register("mercenary", () -> new ForgeSpawnEggItem(
            MERCENARY, 0x3D5A40, 0xE0C060, new Item.Properties()));
    private ModRegistry() {
    }
}
