package com.npctacz.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.npctacz.client.ClientEvents;
import com.npctacz.client.model.GlbSoldierModel;
import com.npctacz.client.model.GlbSoldierTransform;
import com.npctacz.entity.WastelandNpcEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 士兵 NPC 的现代士兵模型渲染器。
 *
 * <p>模型数据来自 {@link GlbSoldierModel}（GLB 离线转换后的 JSON），每个方块网格都是一个
 * 4 顶点 quad，直接喂给 {@link VertexConsumer}。骨骼变换由 {@link GlbSoldierTransform}
 * 每帧算一次，然后按部件 push/mulPose/pop。</p>
 *
 * <p>稳健性：模型缺失、JSON 损坏、贴图加载失败等任何异常都会让渲染器回退到原来的
 * Steve 人形渲染器，绝不让客户端崩在渲染线程里。</p>
 */
public class GlbSoldierRenderer extends EntityRenderer<WastelandNpcEntity> {

    /** 超过这个距离不渲染；308 只 NPC 同屏时这是最主要的省性能手段。 */
    private static final double RENDER_DISTANCE_SQUARED = 24.0D * 24.0D;

    private static final ResourceLocation FALLBACK_TEXTURE =
        new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final String CLIP_IDLE = "idle";
    private static final String CLIP_WALK = "walk";
    private static final String CLIP_GUN_HOLD = "gun_hold";
    private static final String CLIP_ATTACK = "attack";
    private static final String CLIP_RELOAD = "reload";

    /** 手部挂枪用的固定旋转，避免每帧 new。 */
    private static final Quaternionf HAND_ROTATE_X = new Quaternionf().rotateX((float) Math.toRadians(-90.0D));
    private static final Quaternionf HAND_ROTATE_Y = new Quaternionf().rotateY((float) Math.toRadians(180.0D));

    private final EntityRenderer<WastelandNpcEntity> fallback;
    private final Matrix4f yawMatrix = new Matrix4f();
    private GlbSoldierModel model;
    private GlbSoldierTransform transform;
    private int handNodeIndex = -1;
    private Throwable lastLoggedFailure;
    private int renderFailureCount;
    private boolean renderDisabled;

    // ---- 程序化动作（不依赖任何外部动画资源）：抬枪 + 后坐力 + 头部跟随 ----
    private boolean[] armNodes = new boolean[0];
    private boolean[] headNodes = new boolean[0];
    private float[] armPivot = new float[3];
    private float[] headPivot = new float[3];
    private final Matrix4f aimArmMatrix = new Matrix4f();
    private final Matrix4f aimHeadMatrix = new Matrix4f();
    private final Matrix4f extraMatrix = new Matrix4f();
    private final java.util.Map<Integer, Integer> recoilTicks = new java.util.HashMap<>();
    private final java.util.Map<Integer, Boolean> lastFiring = new java.util.HashMap<>();

    private static volatile int lastHandNodeIndex = -1;

    /** 诊断用：模型加载后找到的手骨下标（-1 表示没找到，枪不会显示）。 */
    public static int lastHandNodeIndex() {
        return lastHandNodeIndex;
    }

    public GlbSoldierRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.fallback = new ClientEvents.NpcRenderer(context);
        this.shadowRadius = 0.35F;
        this.shadowStrength = 0.8F;
        LOGGER.info("[npc-tacz] GlbSoldierRenderer created for soldier NPCs");
    }

    @Override
    public boolean shouldRender(WastelandNpcEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        return entity.distanceToSqr(camX, camY, camZ) <= RENDER_DISTANCE_SQUARED
            && super.shouldRender(entity, frustum, camX, camY, camZ);
    }

    @Override
    public ResourceLocation getTextureLocation(WastelandNpcEntity entity) {
        ensureModel();
        return this.model != null ? GlbSoldierModel.TEXTURE : FALLBACK_TEXTURE;
    }

    @Override
    public void render(WastelandNpcEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        // 死亡时用原版人形渲染器，避免自定义 GLB 模型站着“尸体不消失/不倒”
        if (entity.isDeadOrDying() || entity.getHealth() <= 0.0F) {
            this.fallback.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }
        // Distance LOD: the custom GLB mesh is expensive. Beyond 12 blocks use
        // the cheap Steve fallback; the player still sees a soldier silhouette.
        net.minecraft.client.player.LocalPlayer local = Minecraft.getInstance().player;
        if (local != null && entity.distanceToSqr(local) > 12.0D * 12.0D) {
            this.fallback.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }
        // Taunt is a PlayerModel crouch animation; use the humanoid fallback so
        // the GLB mesh does not hide the crouch spam.
        if (entity.isTaunting()) {
            this.fallback.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }
        // 默认使用玩家提供的士兵皮肤模型；/npcmodel glb 可切换回带程序化动作的 GLB 模型
        if (!com.npctacz.client.SoldierModelMode.useGlb()) {
            this.fallback.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }
        ensureModel();
        if (this.model == null || this.transform == null) {
            this.fallback.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }
        if (entity.isInvisible()) {
            return;
        }
        try {
            renderModel(entity, partialTick, poseStack, bufferSource, packedLight);
        } catch (Throwable throwable) {
            // 渲染线程异常不能让客户端崩；连续失败就永久回退到 Steve
            this.renderFailureCount++;
            LOGGER.error("[npc-tacz] soldier model render failed (attempt {})", this.renderFailureCount, throwable);
            if (this.renderFailureCount >= 3) {
                this.renderDisabled = true;
                this.model = null;
                this.transform = null;
            }
            this.fallback.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        }
    }

    private void renderModel(WastelandNpcEntity entity, float partialTick, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight) {
        String clip = pickClip(entity);
        GlbSoldierModel.Animation animation = this.model.animations().get(clip);
        float seconds = this.resolveClipTime(entity, clip, partialTick);
        Matrix4f[] matrices = this.transform.compute(animation, seconds);

        float bodyYaw = Mth.lerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        GlbSoldierModel.Meta meta = this.model.meta();

        this.yawMatrix.identity().rotateY((float) Math.toRadians(180.0D - bodyYaw));
        poseStack.pushPose();
        poseStack.mulPoseMatrix(this.yawMatrix);
        poseStack.translate(meta.offset[0], meta.offset[1], meta.offset[2]);
        poseStack.scale(meta.scale, meta.scale, meta.scale);

        // 抬枪/后坐力/头部跟随：给手臂和头部子树叠加一点本地旋转
        this.computeProceduralPose(entity);

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(GlbSoldierModel.TEXTURE));
        for (GlbSoldierModel.Part part : this.model.parts()) {
            Matrix4f nodeMatrix = matrices[part.node];
            if (nodeMatrix == null) {
                continue;
            }
            if (part.node < this.armNodes.length && this.armNodes[part.node]) {
                nodeMatrix = this.aimArmMatrix.mul(nodeMatrix, this.extraMatrix);
            } else if (part.node < this.headNodes.length && this.headNodes[part.node]) {
                nodeMatrix = this.aimHeadMatrix.mul(nodeMatrix, this.extraMatrix);
            }
            poseStack.pushPose();
            poseStack.mulPoseMatrix(nodeMatrix);
            Matrix4f pose = poseStack.last().pose();
            Matrix3f normalMatrix = poseStack.last().normal();
            float[] verts = part.verts;
            int[] quads = part.quads;
            for (int index = 0; index < quads.length; index += 4) {
                for (int corner = 0; corner < 4; corner++) {
                    int base = quads[index + corner] * 8;
                    consumer.vertex(pose, verts[base], verts[base + 1], verts[base + 2])
                        .color(255, 255, 255, 255)
                        .uv(verts[base + 6], verts[base + 7])
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(packedLight)
                        .normal(normalMatrix, verts[base + 3], verts[base + 4], verts[base + 5])
                        .endVertex();
                }
            }
            poseStack.popPose();
        }
        renderHeldItem(entity, matrices, poseStack, bufferSource, packedLight);
        spawnMuzzleParticles(entity);
        poseStack.popPose();
    }

    /**
     * 程序化动作：
     * <ul>
     *   <li>瞄准时按实体俯仰角抬起右臂（-XRot*0.28）；</li>
     *   <li>开火瞬间给手臂加一点后坐力上踢，6 帧内衰减；</li>
     *   <li>头部跟随目标俯仰（瞄准 0.55 倍，非瞄准 0.35 倍）。</li>
     * </ul>
     */
    private void computeProceduralPose(WastelandNpcEntity entity) {
        int id = entity.getId();
        boolean firing = entity.isFiringAnimation();
        Boolean previous = this.lastFiring.put(id, firing);
        if (firing && (previous == null || !previous)) {
            this.recoilTicks.put(id, 6);
        }
        int recoil = this.recoilTicks.getOrDefault(id, 0);
        if (recoil > 0) {
            this.recoilTicks.put(id, recoil - 1);
        }
        if (this.lastFiring.size() > 256) {
            this.lastFiring.clear();
            this.recoilTicks.clear();
        }
        float pitch = Mth.clamp(entity.getXRot(), -70.0F, 70.0F);
        float aimFactor = entity.isAiming() ? 1.0F : 0.0F;
        float armAngle = (float) Math.toRadians(-pitch * 0.28F * aimFactor + recoil * 2.2F);
        float headAngle = (float) Math.toRadians(-pitch * 0.55F * (entity.isAiming() ? 1.0F : 0.35F));
        buildPivotMatrix(this.aimArmMatrix, this.armPivot, armAngle);
        buildPivotMatrix(this.aimHeadMatrix, this.headPivot, headAngle);
    }

    private static void buildPivotMatrix(Matrix4f out, float[] pivot, float angleRadians) {
        out.identity();
        if (pivot == null || angleRadians == 0.0F) {
            return;
        }
        out.translate(pivot[0], pivot[1], pivot[2]);
        out.rotateX(angleRadians);
        out.translate(-pivot[0], -pivot[1], -pivot[2]);
    }

    /** 开火时在枪口位置喷一点原版烟/火粒子（不使用 TACZ 的粒子资源）。 */
    private void spawnMuzzleParticles(WastelandNpcEntity entity) {
        if (!entity.isFiringAnimation() || entity.tickCount % 2 != 0) {
            return;
        }
        Vec3 view = entity.getViewVector(1.0F);
        Vec3 muzzle = entity.getEyePosition().add(view.scale(0.9D));
        var random = entity.getRandom();
        entity.level().addParticle(ParticleTypes.SMOKE,
            muzzle.x, muzzle.y, muzzle.z,
            view.x * 0.02D, 0.01D, view.z * 0.02D);
        if (random.nextBoolean()) {
            entity.level().addParticle(ParticleTypes.FLAME,
                muzzle.x, muzzle.y, muzzle.z, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * 把主手物品（TACZ 枪）挂到模型的右手骨上。
     *
     * <p>自定义渲染器不是 HumanoidMobRenderer，不会自动渲染手持物品，所以这里照着
     * 原版 {@code ItemInHandLayer} 的手部坐标约定手动摆一次：手骨矩阵 → 手上的
     * -90°X / 180°Y → 偏移 → 用第三人称右手 display context 渲染。</p>
     */
    private void renderHeldItem(WastelandNpcEntity entity, Matrix4f[] matrices, PoseStack poseStack,
                                MultiBufferSource bufferSource, int packedLight) {
        if (this.handNodeIndex < 0) {
            return;
        }
        ItemStack held = entity.getMainHandItem();
        if (held.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        poseStack.mulPoseMatrix(matrices[this.handNodeIndex]);
        poseStack.mulPose(HAND_ROTATE_X);
        poseStack.mulPose(HAND_ROTATE_Y);
        poseStack.translate(1.0D / 16.0D, 0.125D, -0.625D);
        poseStack.scale(0.9F, 0.9F, 0.9F);
        Minecraft.getInstance().getItemRenderer().renderStatic(
            held,
            ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            poseStack,
            bufferSource,
            entity.level(),
            entity.getId()
        );
        poseStack.popPose();
    }

    /**
     * 选动画（只用模型自带的动画，没有任何外部资源）：
     * 开火 → attack，换弹 → reload，移动 → walk，有敌人 → gun_hold，否则 idle。
     */
    private static String pickClip(WastelandNpcEntity entity) {
        if (entity.isFiringAnimation()) {
            return CLIP_ATTACK;
        }
        if (entity.isReloadingAnimation()) {
            return CLIP_RELOAD;
        }
        if (entity.isAiming()) {
            return CLIP_GUN_HOLD;
        }
        if (entity.getDeltaMovement().horizontalDistanceSqr() > 2.5E-3D) {
            return CLIP_WALK;
        }
        return CLIP_IDLE;
    }

    /** 每个实体的动画播放起点，只存在于客户端。 */
    private static final class Playback {
        private final String clip;
        private final float startSeconds;

        private Playback(String clip, float startSeconds) {
            this.clip = clip;
            this.startSeconds = startSeconds;
        }
    }

    private final java.util.Map<Integer, Playback> playbacks = new java.util.HashMap<>();

    /**
     * 计算当前动画的播放时间：
     * <ul>
     *   <li>walk：用 Minecraft 的走路相位驱动，脚步和实际移动同步，不会滑步；</li>
     *   <li>其他：按各自时长循环（attack 连发时循环播放，不会卡在最后一帧）。</li>
     * </ul>
     */
    private float resolveClipTime(WastelandNpcEntity entity, String clip, float partialTick) {
        float now = entity.tickCount / 20.0F + partialTick / 20.0F;
        Playback playback = this.playbacks.get(entity.getId());
        if (playback == null || !playback.clip.equals(clip)) {
            playback = new Playback(clip, now);
            this.playbacks.put(entity.getId(), playback);
            if (this.playbacks.size() > 512) {
                this.playbacks.clear();
            }
        }
        GlbSoldierModel.Animation animation = this.model.animations().get(clip);
        float duration = animation == null ? 1.0F : Math.max(0.05F, animation.duration);
        if (CLIP_WALK.equals(clip)) {
            return entity.walkAnimation.position(partialTick) * duration;
        }
        float local = now - playback.startSeconds;
        return local % duration;
    }

    private static int firstExisting(GlbSoldierModel model, String... names) {
        for (String name : names) {
            int index = model.nodeIndex(name);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    /** 某个骨骼节点及其所有子孙节点的掩码（用于整体旋转手臂/头部）。 */
    private static boolean[] subtreeMask(GlbSoldierModel model, int root) {
        GlbSoldierModel.Node[] nodes = model.nodes();
        boolean[] mask = new boolean[nodes.length];
        if (root < 0 || root >= nodes.length) {
            return mask;
        }
        for (int i = 0; i < nodes.length; i++) {
            int cursor = i;
            for (int guard = 0; guard < 128 && cursor >= 0 && cursor < nodes.length; guard++) {
                if (cursor == root) {
                    mask[i] = true;
                    break;
                }
                cursor = nodes[cursor].parent;
            }
        }
        return mask;
    }

    private void ensureModel() {
        if (this.model != null || this.renderDisabled) {
            return;
        }
        try {
            GlbSoldierModel loaded = GlbSoldierModel.get();
            this.model = loaded;
            this.transform = new GlbSoldierTransform(loaded);
            this.handNodeIndex = firstExisting(loaded,
                "hand_r", "fore_right", "arm_right", "hand_l");
            lastHandNodeIndex = this.handNodeIndex;
            int armRoot = loaded.nodeIndex("arm_right");
            int headRoot = loaded.nodeIndex("head");
            this.armNodes = subtreeMask(loaded, armRoot);
            this.headNodes = subtreeMask(loaded, headRoot);
            if (armRoot >= 0) {
                this.armPivot = loaded.nodes()[armRoot].pivot;
            }
            if (headRoot >= 0) {
                this.headPivot = loaded.nodes()[headRoot].pivot;
            }
            LOGGER.info("[npc-tacz] modern soldier model loaded: parts={} nodes={} animations={} handNode={} armRoot={} headRoot={}",
                loaded.parts().length, loaded.nodes().length, loaded.animations().size(),
                this.handNodeIndex, armRoot, headRoot);
        } catch (Throwable throwable) {
            Throwable cause = GlbSoldierModel.lastFailure();
            if (cause != this.lastLoggedFailure) {
                this.lastLoggedFailure = cause;
                LOGGER.error("[npc-tacz] modern soldier model failed to load, falling back to Steve", throwable);
            }
        }
    }
}
