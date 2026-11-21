package com.npctacz.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 现代士兵模型（GLB 离线转换后的 JSON）的客户端运行时数据。
 *
 * <p>数据格式是给这个渲染器专门准备的，不是 Minecraft 原版模型格式：</p>
 * <ul>
 *   <li><b>parts</b>：103 个方块网格，每个 24 顶点（位置 xyz / 法线 xyz / UV uv）+ 6 个 quad；</li>
 *   <li><b>nodes</b>：骨骼层级，每个节点带绑定平移 t、绝对旋转轴心 pivot；</li>
 *   <li><b>animations</b>：7 个关键帧动画（idle / walk / attack / gun_hold / reload / head_down / gun_head_down）。</li>
 * </ul>
 *
 * <p>坐标约定：模型空间 Y 向上、正面朝 -Z、脚底在原始 y=-0.5；meta.scale / meta.offset
 * 已经把模型缩放到 1.8 格高并让脚底贴近实体原点。</p>
 *
 * <p>加载失败时抛异常，渲染器会接住并回退到原来的 Steve 渲染器，避免客户端崩溃。</p>
 */
public final class GlbSoldierModel {

    public static final ResourceLocation MODEL_RESOURCE =
        new ResourceLocation("npc_tacz", "models/entity/modern_soldier.json");
    public static final ResourceLocation TEXTURE =
        new ResourceLocation("npc_tacz", "textures/entity/modern_soldier.png");

    public static final class Node {
        public final String name;
        public final int parent;
        public final float[] t;
        public final float[] pivot;
        public final int mesh;

        Node(String name, int parent, float[] t, float[] pivot, int mesh) {
            this.name = name;
            this.parent = parent;
            this.t = t;
            this.pivot = pivot;
            this.mesh = mesh;
        }
    }

    public static final class Part {
        public final int node;
        public final float[] verts;
        public final int[] quads;

        Part(int node, float[] verts, int[] quads) {
            this.node = node;
            this.verts = verts;
            this.quads = quads;
        }
    }

    public static final class Channel {
        public final int node;
        public final String path;
        public final float[] times;
        public final float[] values;

        Channel(int node, String path, float[] times, float[] values) {
            this.node = node;
            this.path = path;
            this.times = times;
            this.values = values;
        }
    }

    public static final class Animation {
        public final String name;
        public final float duration;
        public final Channel[] channels;

        Animation(String name, float duration, Channel[] channels) {
            this.name = name;
            this.duration = duration;
            this.channels = channels;
        }
    }

    public static final class Meta {
        public final float scale;
        public final float[] offset;

        Meta(float scale, float[] offset) {
            this.scale = scale;
            this.offset = offset;
        }
    }

    private static volatile GlbSoldierModel instance;
    private static volatile Throwable lastFailure;
    private static volatile long lastFailureAt;
    /** 失败后 5 秒内不重试，避免每帧都去读文件。 */
    private static final long RETRY_INTERVAL_MS = 5000L;

    private final Node[] nodes;
    private final Part[] parts;
    private final Map<String, Animation> animations;
    private final Map<String, Integer> nodeNames = new HashMap<>();
    private final Meta meta;
    /** 父节点一定排在子节点之前的渲染顺序。 */
    private final int[] order;

    public static GlbSoldierModel get() {
        GlbSoldierModel local = instance;
        if (local != null) {
            return local;
        }
        if (lastFailure != null && System.currentTimeMillis() - lastFailureAt < RETRY_INTERVAL_MS) {
            throw new IllegalStateException("soldier model failed recently: " + lastFailure, lastFailure);
        }
        synchronized (GlbSoldierModel.class) {
            if (instance == null) {
                try {
                    instance = load();
                    lastFailure = null;
                } catch (Throwable throwable) {
                    lastFailure = throwable;
                    lastFailureAt = System.currentTimeMillis();
                    throw new IllegalStateException("failed to load modern soldier model", throwable);
                }
            }
            return instance;
        }
    }

    /** 已经加载好的实例（不触发加载、不抛异常），给诊断指令用。 */
    public static GlbSoldierModel peek() {
        return instance;
    }

    /** 最近一次加载失败的原因，给诊断指令用。 */
    public static Throwable lastFailure() {
        return lastFailure;
    }

    /** 诊断指令里强制重试一次。 */
    public static void forceReload() {
        synchronized (GlbSoldierModel.class) {
            instance = null;
            lastFailure = null;
            lastFailureAt = 0L;
        }
    }

    private static GlbSoldierModel load() throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<Resource> resource = minecraft.getResourceManager().getResource(MODEL_RESOURCE);
        if (resource.isEmpty()) {
            throw new IllegalStateException("missing resource " + MODEL_RESOURCE);
        }
        try (InputStreamReader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return new GlbSoldierModel(root);
        }
    }

    private GlbSoldierModel(JsonObject root) {
        JsonObject metaJson = root.getAsJsonObject("meta");
        this.meta = new Meta(metaJson.get("scale").getAsFloat(), floats(metaJson.getAsJsonArray("offset")));

        JsonArray nodeArray = root.getAsJsonArray("nodes");
        this.nodes = new Node[nodeArray.size()];
        for (int i = 0; i < nodeArray.size(); i++) {
            JsonObject nodeJson = nodeArray.get(i).getAsJsonObject();
            this.nodes[i] = new Node(
                nodeJson.has("name") ? nodeJson.get("name").getAsString() : "",
                nodeJson.get("parent").getAsInt(),
                floats(nodeJson.getAsJsonArray("t")),
                floats(nodeJson.getAsJsonArray("pivot")),
                nodeJson.get("mesh").getAsInt()
            );
            this.nodeNames.put(this.nodes[i].name, i);
        }

        JsonArray partArray = root.getAsJsonArray("parts");
        this.parts = new Part[partArray.size()];
        for (int i = 0; i < partArray.size(); i++) {
            JsonObject partJson = partArray.get(i).getAsJsonObject();
            this.parts[i] = new Part(
                partJson.get("node").getAsInt(),
                floats(partJson.getAsJsonArray("verts")),
                ints(partJson.getAsJsonArray("quads"))
            );
        }

        Map<String, Animation> animationMap = new HashMap<>();
        JsonObject animationRoot = root.getAsJsonObject("animations");
        for (Map.Entry<String, com.google.gson.JsonElement> entry : animationRoot.entrySet()) {
            JsonObject animationJson = entry.getValue().getAsJsonObject();
            JsonArray channelArray = animationJson.getAsJsonArray("channels");
            Channel[] channels = new Channel[channelArray.size()];
            for (int i = 0; i < channelArray.size(); i++) {
                JsonObject channelJson = channelArray.get(i).getAsJsonObject();
                channels[i] = new Channel(
                    channelJson.get("node").getAsInt(),
                    channelJson.get("path").getAsString(),
                    floats(channelJson.getAsJsonArray("times")),
                    floats(channelJson.getAsJsonArray("values"))
                );
            }
            animationMap.put(entry.getKey(), new Animation(
                entry.getKey(), animationJson.get("duration").getAsFloat(), channels
            ));
        }
        this.animations = Collections.unmodifiableMap(animationMap);
        this.order = buildOrder();
    }

    private int[] buildOrder() {
        int count = this.nodes.length;
        int[] result = new int[count];
        boolean[] placed = new boolean[count];
        int index = 0;
        for (int i = 0; i < count; i++) {
            index = place(i, result, placed, index);
        }
        return result;
    }

    private int place(int node, int[] result, boolean[] placed, int index) {
        if (placed[node]) {
            return index;
        }
        int parent = this.nodes[node].parent;
        int next = index;
        if (parent >= 0) {
            next = place(parent, result, placed, next);
        }
        result[next] = node;
        placed[node] = true;
        return next + 1;
    }

    private static float[] floats(JsonArray array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsFloat();
        }
        return values;
    }

    private static int[] ints(JsonArray array) {
        int[] values = new int[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsInt();
        }
        return values;
    }

    public Node[] nodes() {
        return this.nodes;
    }

    public Part[] parts() {
        return this.parts;
    }

    public Map<String, Animation> animations() {
        return this.animations;
    }

    public Meta meta() {
        return this.meta;
    }

    public int[] order() {
        return this.order;
    }

    /** 按骨骼名字查节点下标，找不到返回 -1（用来找手骨挂物品）。 */
    public int nodeIndex(String name) {
        Integer index = this.nodeNames.get(name);
        return index == null ? -1 : index;
    }
}
