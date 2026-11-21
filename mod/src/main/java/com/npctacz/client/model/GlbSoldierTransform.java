package com.npctacz.client.model;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 现代士兵模型的骨骼变换与动画采样。
 *
 * <p>这套模型不是 Minecraft 原版模型格式，而是一份 Blockbench 导出的 GLB：顶点在
 * <b>模型空间</b>里，骨骼节点只负责给出"绕哪根轴旋转"（轴心 pivot）。所以换算方式是：</p>
 *
 * <pre>
 *   A_node = T(delta) * T(pivot) * R * T(-pivot)
 *   M_node = M_parent * A_node
 * </pre>
 *
 * <p>其中 R 来自动画通道（没有动画通道时是单位四元数），delta 是动画 translation 通道
 * 与绑定平移之差。绑定姿势下所有权重都退化为单位矩阵，模型直接就是原始顶点。</p>
 *
 * <p>本类不做任何分配：矩阵、四元数和中间数组都在实例化时准备好，每帧原地复用，
 * 避免大量 NPC 同时渲染时的 GC 压力。</p>
 */
public final class GlbSoldierTransform {

    private final GlbSoldierModel model;
    private final int nodeCount;
    private final int[] order;
    private final Matrix4f[] matrices;
    private final Matrix4f local = new Matrix4f();
    private final float[] rotation;
    private final float[] translation;
    private final Quaternionf quaternion = new Quaternionf();
    private final Quaternionf otherQuaternion = new Quaternionf();

    public GlbSoldierTransform(GlbSoldierModel model) {
        this.model = model;
        this.nodeCount = model.nodes().length;
        this.order = model.order();
        this.matrices = new Matrix4f[this.nodeCount];
        for (int i = 0; i < this.nodeCount; i++) {
            this.matrices[i] = new Matrix4f();
        }
        this.rotation = new float[this.nodeCount * 4];
        this.translation = new float[this.nodeCount * 3];
    }

    /**
     * 计算当前帧所有节点的模型矩阵（结果数组由本对象持有，不要保存引用）。
     *
     * @param animation   动画；null 表示绑定姿势
     * @param timeSeconds 动画时间（秒），内部会按动画时长循环
     */
    public Matrix4f[] compute(GlbSoldierModel.Animation animation, float timeSeconds) {
        resetPose();
        if (animation != null) {
            applyAnimation(animation, timeSeconds);
        }
        for (int i = 0; i < this.order.length; i++) {
            int index = this.order[i];
            GlbSoldierModel.Node node = this.model.nodes()[index];
            Matrix4f matrix = this.matrices[index];
            int parent = node.parent;
            if (parent >= 0) {
                matrix.set(this.matrices[parent]);
            } else {
                matrix.identity();
            }
            float px = node.pivot[0];
            float py = node.pivot[1];
            float pz = node.pivot[2];
            this.quaternion.set(
                this.rotation[index * 4],
                this.rotation[index * 4 + 1],
                this.rotation[index * 4 + 2],
                this.rotation[index * 4 + 3]
            );
            this.local.identity()
                .translate(
                    this.translation[index * 3] - node.t[0],
                    this.translation[index * 3 + 1] - node.t[1],
                    this.translation[index * 3 + 2] - node.t[2]
                )
                .translate(px, py, pz)
                .rotate(this.quaternion)
                .translate(-px, -py, -pz);
            matrix.mul(this.local);
        }
        return this.matrices;
    }

    private void resetPose() {
        GlbSoldierModel.Node[] nodes = this.model.nodes();
        for (int i = 0; i < this.nodeCount; i++) {
            GlbSoldierModel.Node node = nodes[i];
            this.rotation[i * 4] = 0.0F;
            this.rotation[i * 4 + 1] = 0.0F;
            this.rotation[i * 4 + 2] = 0.0F;
            this.rotation[i * 4 + 3] = 1.0F;
            this.translation[i * 3] = node.t[0];
            this.translation[i * 3 + 1] = node.t[1];
            this.translation[i * 3 + 2] = node.t[2];
        }
    }

    private void applyAnimation(GlbSoldierModel.Animation animation, float timeSeconds) {
        float duration = Math.max(1.0E-4F, animation.duration);
        float time = timeSeconds % duration;
        if (time < 0.0F) {
            time += duration;
        }
        for (GlbSoldierModel.Channel channel : animation.channels) {
            int index = channel.node;
            if (index < 0 || index >= this.nodeCount) {
                continue;
            }
            if ("rotation".equals(channel.path)) {
                sampleRotation(channel, time, index);
            } else if ("translation".equals(channel.path)) {
                sampleTranslation(channel, time, index);
            }
        }
    }

    private void sampleRotation(GlbSoldierModel.Channel channel, float time, int node) {
        float[] times = channel.times;
        float[] values = channel.values;
        int count = times.length;
        int offset = node * 4;
        if (count == 0) {
            return;
        }
        if (time <= times[0] || count == 1) {
            this.rotation[offset] = values[0];
            this.rotation[offset + 1] = values[1];
            this.rotation[offset + 2] = values[2];
            this.rotation[offset + 3] = values[3];
            return;
        }
        if (time >= times[count - 1]) {
            int base = (count - 1) * 4;
            this.rotation[offset] = values[base];
            this.rotation[offset + 1] = values[base + 1];
            this.rotation[offset + 2] = values[base + 2];
            this.rotation[offset + 3] = values[base + 3];
            return;
        }
        int segment = findSegment(times, time);
        int a = segment * 4;
        int b = a + 4;
        float fraction = (time - times[segment]) / (times[segment + 1] - times[segment]);
        this.quaternion.set(values[a], values[a + 1], values[a + 2], values[a + 3]);
        this.otherQuaternion.set(values[b], values[b + 1], values[b + 2], values[b + 3]);
        this.quaternion.slerp(this.otherQuaternion, fraction);
        this.rotation[offset] = this.quaternion.x;
        this.rotation[offset + 1] = this.quaternion.y;
        this.rotation[offset + 2] = this.quaternion.z;
        this.rotation[offset + 3] = this.quaternion.w;
    }

    private void sampleTranslation(GlbSoldierModel.Channel channel, float time, int node) {
        float[] times = channel.times;
        float[] values = channel.values;
        int count = times.length;
        int offset = node * 3;
        if (count == 0) {
            return;
        }
        if (time <= times[0] || count == 1) {
            this.translation[offset] = values[0];
            this.translation[offset + 1] = values[1];
            this.translation[offset + 2] = values[2];
            return;
        }
        if (time >= times[count - 1]) {
            int base = (count - 1) * 3;
            this.translation[offset] = values[base];
            this.translation[offset + 1] = values[base + 1];
            this.translation[offset + 2] = values[base + 2];
            return;
        }
        int segment = findSegment(times, time);
        int a = segment * 3;
        int b = a + 3;
        float fraction = (time - times[segment]) / (times[segment + 1] - times[segment]);
        this.translation[offset] = values[a] + (values[b] - values[a]) * fraction;
        this.translation[offset + 1] = values[a + 1] + (values[b + 1] - values[a + 1]) * fraction;
        this.translation[offset + 2] = values[a + 2] + (values[b + 2] - values[a + 2]) * fraction;
    }

    private static int findSegment(float[] times, float time) {
        int low = 0;
        int high = times.length - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= time) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
