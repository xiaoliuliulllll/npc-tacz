package com.npctacz;

import com.npctacz.config.NpcConfig;
import com.npctacz.config.NpcGlobalConfig;
import com.npctacz.network.NpcNetwork;
import com.npctacz.entity.BanditNpc;
import com.npctacz.entity.BountyHunterNpc;
import com.npctacz.entity.MercenaryNpc;
import com.npctacz.entity.RebelNpc;
import com.npctacz.entity.SoldierNpc;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(ModRegistry.MOD_ID)
public final class NpcTaczMod {
    private static final Logger LOGGER = LogUtils.getLogger();
    public NpcTaczMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModRegistry.ENTITY_TYPES.register(bus);
        ModRegistry.SPAWN_ITEMS.register(bus);
        // 战斗/寻路参数：自动生成 config/npc-tacz-common.toml，无需手动添加
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, NpcConfig.SPEC);
        // 读取 /config/tacz-npc-modif 全局修改文件（不存在时生成默认模板）
        NpcGlobalConfig.load();
        LOGGER.info("[npc-tacz] loaded build fallback-watchdog-v2");
        // 客户端反馈网络（NPC 命中玩家时的视角后坐力）
        NpcNetwork.init();
        MinecraftForge.EVENT_BUS.register(com.npctacz.event.NpcTickWatchdog.class);
        LOGGER.info("[npc-tacz] watchdog explicitly registered");
    }

    @Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void attributes(EntityAttributeCreationEvent event) {
            event.put(ModRegistry.SOLDIER.get(), SoldierNpc.createAttributes().build());
            event.put(ModRegistry.BANDIT.get(), BanditNpc.createAttributes().build());
            event.put(ModRegistry.REBEL.get(), RebelNpc.createAttributes().build());
            event.put(ModRegistry.BOUNTY_HUNTER.get(), BountyHunterNpc.createAttributes().build());
            event.put(ModRegistry.MERCENARY.get(), MercenaryNpc.createAttributes().build());
        }
    }
}

