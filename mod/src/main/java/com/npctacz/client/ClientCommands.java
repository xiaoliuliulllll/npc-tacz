package com.npctacz.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.npctacz.client.model.GlbSoldierModel;
import com.npctacz.client.render.GlbSoldierRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端模型指令：
 *
 * <pre>
 *   /npcmodel          查看当前模式与模型加载状态
 *   /npcmodel skin     士兵使用玩家提供的皮肤模型（默认）
 *   /npcmodel glb      士兵使用朋友的 GLB 模型（带抬枪/后坐力/换弹动作）
 *   /npcmodel reload   清掉失败状态，下一次渲染时重试加载 GLB
 * </pre>
 */
@Mod.EventBusSubscriber(modid = com.npctacz.ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientCommands {

    private ClientCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("npcmodel")
            .executes(ClientCommands::status)
            .then(Commands.literal("skin").executes(ClientCommands::useSkin))
            .then(Commands.literal("glb").executes(ClientCommands::useGlb))
            .then(Commands.literal("reload").executes(ClientCommands::reload)));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        String mode = SoldierModelMode.useGlb() ? "§bGLB 动画模型" : "§a皮肤模型";
        print("§e[npc-tacz] 士兵当前渲染模式：" + mode);
        if (!SoldierModelMode.useGlb()) {
            print("§7  输入 /npcmodel glb 可切换回朋友的 GLB 模型（带动作）");
            return 1;
        }
        GlbSoldierModel model = GlbSoldierModel.peek();
        if (model != null) {
            print("§a[npc-tacz] 士兵 GLB 模型已加载：parts=" + model.parts().length
                + "，nodes=" + model.nodes().length
                + "，animations=" + model.animations().size()
                + "，handNode=" + GlbSoldierRenderer.lastHandNodeIndex()
                + "，scale=" + model.meta().scale);
            return 1;
        }
        Throwable failure = GlbSoldierModel.lastFailure();
        if (failure == null) {
            print("§e[npc-tacz] 士兵 GLB 模型还没开始加载（还没有士兵进入渲染范围？）");
        } else {
            print("§c[npc-tacz] 士兵 GLB 模型加载失败：" + failure);
        }
        return 0;
    }

    private static int useSkin(CommandContext<CommandSourceStack> context) {
        SoldierModelMode.setUseGlb(false);
        print("§a[npc-tacz] 士兵已切换为皮肤模型（/npcmodel glb 可换回 GLB）");
        return 1;
    }

    private static int useGlb(CommandContext<CommandSourceStack> context) {
        SoldierModelMode.setUseGlb(true);
        print("§a[npc-tacz] 士兵已切换为 GLB 动画模型（/npcmodel skin 可换回皮肤）");
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        GlbSoldierModel.forceReload();
        print("§e[npc-tacz] 已重置模型状态，下一次渲染会重试加载（看 logs/latest.log 里的 [npc-tacz] 行）");
        return 1;
    }

    private static void print(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), false);
        }
    }
}
