package com.npctacz.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.culling.Frustum;
import com.npctacz.ModRegistry;
import com.npctacz.client.render.GlbSoldierRenderer;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


/**
 * Temporary player-shaped renderer. It references the vanilla default skin at runtime
 * and does not ship any Minecraft texture in the mod jar.
 */
@Mod.EventBusSubscriber(modid = ModRegistry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 士兵使用朋友做的现代士兵 GLB 模型（自带回退：模型坏了自动变回 Steve）
        event.registerEntityRenderer(ModRegistry.SOLDIER.get(), GlbSoldierRenderer::new);
        event.registerEntityRenderer(ModRegistry.BANDIT.get(), NpcRenderer::new);
        event.registerEntityRenderer(ModRegistry.REBEL.get(), NpcRenderer::new);
        event.registerEntityRenderer(ModRegistry.BOUNTY_HUNTER.get(), NpcRenderer::new);
        event.registerEntityRenderer(ModRegistry.MERCENARY.get(), NpcRenderer::new);
    }

    private ClientEvents() {
    }

    public static final class NpcRenderer extends HumanoidMobRenderer<WastelandNpcEntity, PlayerModel<WastelandNpcEntity>> {
        private static final ResourceLocation FALLBACK_TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
        /** 玩家提供的三个阵营皮肤（64x64 现代皮肤布局）。 */
        private static final ResourceLocation SOLDIER_TEXTURE =
            new ResourceLocation("npc_tacz", "textures/entity/soldier.png");
        private static final ResourceLocation BANDIT_TEXTURE =
            new ResourceLocation("npc_tacz", "textures/entity/bandit.png");
        private static final ResourceLocation REBEL_TEXTURE =
            new ResourceLocation("npc_tacz", "textures/entity/rebel.png");
        private static final ResourceLocation MERCENARY_TEXTURE =
            new ResourceLocation("npc_tacz", "textures/entity/mercenary.png");
        private static final ResourceLocation BOUNTY_HUNTER_TEXTURE =
            new ResourceLocation("npc_tacz", "textures/entity/bounty_hunter.png");

        public NpcRenderer(EntityRendererProvider.Context context) {
            super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
            this.addLayer(new LodArmorLayer(
                context,
                this,
                context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR),
                context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)
            ));
            this.shadowRadius = 0.3F;
            this.shadowStrength = 0.6F;
        }

        @Override
        public boolean shouldRender(WastelandNpcEntity entity, Frustum frustum,
                                    double camX, double camY, double camZ) {
            // Frame-rate guard: do not render distant NPC bodies/models.
            return entity.distanceToSqr(camX, camY, camZ) <= 40.0D * 40.0D
                && super.shouldRender(entity, frustum, camX, camY, camZ);
        }

        @Override
        public ResourceLocation getTextureLocation(WastelandNpcEntity entity) {
            return switch (entity.getFaction()) {
                case SOLDIER -> SOLDIER_TEXTURE;
                case BANDIT -> BANDIT_TEXTURE;
                case REBEL -> REBEL_TEXTURE;
                case MERCENARY -> MERCENARY_TEXTURE;
                case BOUNTY_HUNTER -> BOUNTY_HUNTER_TEXTURE;
                default -> FALLBACK_TEXTURE;
            };
        }

        @Override
        protected void scale(WastelandNpcEntity entity, PoseStack poseStack, float partialTickTime) {
            poseStack.scale(0.95F, 0.95F, 0.95F);
        }
    }

    /** Armor is the most expensive humanoid layer; skip it beyond 24 blocks. */
    private static final class LodArmorLayer extends HumanoidArmorLayer<
        WastelandNpcEntity,
        PlayerModel<WastelandNpcEntity>,
        HumanoidModel<WastelandNpcEntity>> {

        private final HumanoidModel<WastelandNpcEntity> inner;
        private final HumanoidModel<WastelandNpcEntity> outer;

        private LodArmorLayer(EntityRendererProvider.Context context, NpcRenderer parent,
                              net.minecraft.client.model.geom.ModelPart innerPart,
                              net.minecraft.client.model.geom.ModelPart outerPart) {
            super(parent,
                new HumanoidModel<>(innerPart),
                new HumanoidModel<>(outerPart),
                context.getModelManager());
            this.inner = new HumanoidModel<>(innerPart);
            this.outer = new HumanoidModel<>(outerPart);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                           WastelandNpcEntity entity, float limbSwing, float limbSwingAmount,
                           float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
            var local = Minecraft.getInstance().player;
            if (local != null && entity.distanceToSqr(local) > 24.0D * 24.0D) {
                return;
            }
            super.render(poseStack, bufferSource, packedLight, entity, limbSwing, limbSwingAmount,
                partialTicks, ageInTicks, netHeadYaw, headPitch);
        }
    }
}

