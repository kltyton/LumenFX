package com.kltyton.lumenfx.runtime.model;

import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.annotation.Nullable;

/**
 * Exact 26.2 Photon {@code .fx} NBT parser reading {@code fxData.fxObjects[]}, transform hierarchy
 * ({@code id}, {@code _parentId}, {@code localPosition}, {@code localRotation}, {@code localScale}),
 * {@code config.renderer.renderMode} ({@code Model}, {@code Billboard}, {@code StretchedBillboard}, {@code Mesh}),
 * authored {@code model.source} ({@code json_model} -> {@code modelLocation}), and material textures/blend modes.
 */
public final class PhotonFxModel {
    public enum ObjectType { EMPTY, PARTICLE, TRAIL, BEAM, MESH }
    public enum RenderMode { BILLBOARD, STRETCHED_BILLBOARD, MODEL, MESH, TRAIL, BEAM }

    public record TransformSpec(String id, @Nullable String parentId,
                                float posX, float posY, float posZ,
                                float rotX, float rotY, float rotZ, float rotW,
                                float scaleX, float scaleY, float scaleZ) { }

    public record FxObjectSpec(String name, ObjectType type, TransformSpec transform,
                               RenderMode renderMode, boolean localSpace,
                               float durationSeconds, boolean looping, float delaySeconds,
                               float emissionRate, int burstCount,
                               float startLifetimeSeconds, float startSpeed, float startSize,
                               float r, float g, float b, float a,
                               @Nullable String textureId, BlendMode blendMode,
                               boolean depthTest, boolean depthMask, boolean cull,
                               @Nullable String modelLocation, @Nullable StaticMeshModel authoredModel) {
        public MaterialModel material() {
            Map<String, String> tex = (textureId != null && !textureId.isBlank())
                    ? Map.of("Sampler0", textureId) : Map.of();
            return new MaterialModel(name, "lumenfx:core/effect", null, tex, blendMode,
                    depthTest, depthMask, cull, new float[4], false, "");
        }
    }

    private final String id;
    private final List<FxObjectSpec> objects;

    public PhotonFxModel(String id, List<FxObjectSpec> objects) {
        this.id = id;
        this.objects = List.copyOf(objects);
    }

    public String id() { return id; }
    public List<FxObjectSpec> objects() { return objects; }

    public boolean hasAuthoredModel() {
        for (FxObjectSpec o : objects) {
            if (o.renderMode() == RenderMode.MODEL && o.authoredModel() != null) return true;
        }
        return false;
    }

    public static @Nullable PhotonFxModel parseNbt(String effectId, byte[] bytes, @Nullable McResourceBridge bridge) {
        if (bytes == null || bytes.length < 3) return null;
        try {
            Map<String, Object> root = readRootCompound(bytes);
            if (root == null) return null;
            // Photon 26.2 wraps objects in fxData.fxObjects[]
            Map<String, Object> container = asMap(root.get("fxData"));
            if (container == null) container = root;

            List<FxObjectSpec> specs = new ArrayList<>();
            Object listObj = container.containsKey("fxObjects") ? container.get("fxObjects") : container.get("emitters");
            if (listObj instanceof List<?> list) {
                int index = 0;
                for (Object item : list) {
                    Map<String, Object> rawObj = asMap(item);
                    if (rawObj == null) continue;
                    specs.add(parseFxObject(rawObj, bridge, "photon_" + (index++)));
                }
            }
            if (specs.isEmpty()) {
                specs.add(parseFxObject(container, bridge, "photon_root"));
            }
            return new PhotonFxModel(effectId, specs);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static FxObjectSpec parseFxObject(Map<String, Object> entry, @Nullable McResourceBridge bridge, String fallbackName) {
        String rawType = readString(entry, "type", readString(entry, "_type", "particle")).toLowerCase(Locale.ROOT);
        Map<String, Object> data = asMap(entry.get("data"));
        if (data == null) data = entry;

        String name = readString(data, "name", fallbackName);
        ObjectType objType = switch (rawType) {
            case "empty", "group" -> ObjectType.EMPTY;
            case "trail" -> ObjectType.TRAIL;
            case "beam" -> ObjectType.BEAM;
            case "mesh" -> ObjectType.MESH;
            default -> ObjectType.PARTICLE;
        };

        Map<String, Object> rawTransform = asMap(data.get("transform"));
        if (rawTransform == null) rawTransform = asMap(data.get("_transform"));
        TransformSpec transform = parseTransform(rawTransform);
        Map<String, Object> config = asMap(data.get("config"));
        if (config == null) config = data;

        boolean localSpace = !readString(config, "simulationSpace", "Local").equalsIgnoreCase("World");
        float durationTicks = Math.max(1f, readFloat(config, "duration", 100f));
        boolean looping = readBool(config, "isLooping", readBool(config, "looping", true));
        float delayTicks = Math.max(0f, readFloat(config, "startDelay", readFloat(config, "delay", 0f)));

        float emissionRate = 10f;
        int burstCount = 0;
        Map<String, Object> emission = asMap(config.get("emission"));
        if (emission != null) {
            emissionRate = readNumberOrConstant(emission.get("emissionRate"), 10f);
            Object burstsObj = emission.get("bursts");
            if (burstsObj instanceof List<?> bList) {
                for (Object bItem : bList) {
                    Map<String, Object> bMap = asMap(bItem);
                    if (bMap != null) {
                        burstCount += Math.round(readNumberOrConstant(bMap.get("count"), 0f));
                    }
                }
            }
        } else {
            emissionRate = readNumberOrConstant(config.get("emissionRate"), 10f);
        }

        float lifeTicks = Math.max(1f, readNumberOrConstant(config.get("startLifetime"), 40f));
        float startSpeed = readNumberOrConstant(config.get("startSpeed"), 0.5f);
        float startSize = Math.max(0.02f, readNumberOrConstant(config.get("startSize"), 1.0f));
        int argb = readInt(config, "startColor", 0xFFFFFFFF);
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        if (a <= 0f) a = 1f;

        RenderMode renderMode = objType == ObjectType.TRAIL ? RenderMode.TRAIL
                : objType == ObjectType.BEAM ? RenderMode.BEAM
                : objType == ObjectType.MESH ? RenderMode.MESH : RenderMode.BILLBOARD;
        String textureId = null;
        BlendMode blendMode = BlendMode.ALPHA;
        boolean depthTest = true;
        boolean depthMask = false;
        boolean cull = false;
        String modelLocation = null;
        StaticMeshModel authoredModel = null;

        Map<String, Object> renderer = asMap(config.get("renderer"));
        if (renderer != null) {
            String rm = readString(renderer, "renderMode", "").toLowerCase(Locale.ROOT);
            if (rm.equals("model")) renderMode = RenderMode.MODEL;
            else if (rm.contains("mesh")) renderMode = RenderMode.MESH;
            else if (rm.contains("stretch")) renderMode = RenderMode.STRETCHED_BILLBOARD;
            else if (rm.contains("billboard")) renderMode = RenderMode.BILLBOARD;

            // Extract materials.payload[0]
            Map<String, Object> mats = asMap(renderer.get("materials"));
            if (mats != null && mats.get("payload") instanceof List<?> payload && !payload.isEmpty()) {
                Map<String, Object> m0 = asMap(payload.get(0));
                if (m0 != null) {
                    depthTest = readBool(m0, "depthTest", true);
                    depthMask = readBool(m0, "depthMask", false);
                    cull = readBool(m0, "cull", false);
                    Map<String, Object> matNode = asMap(m0.get("material"));
                    if (matNode != null) {
                        Map<String, Object> matData = asMap(matNode.get("data"));
                        if (matData != null) {
                            textureId = readNullableString(matData, "texture", null);
                        }
                    }
                    Map<String, Object> bm = asMap(m0.get("blendMode"));
                    if (bm != null) {
                        boolean enableBlend = readBool(bm, "enableBlend", true);
                        String dstColor = readString(bm, "dstColorFactor", "ONE_MINUS_SRC_ALPHA").toUpperCase(Locale.ROOT);
                        if (!enableBlend) blendMode = BlendMode.OPAQUE;
                        else if (dstColor.equals("ONE")) blendMode = BlendMode.ADDITIVE;
                        else blendMode = BlendMode.ALPHA;
                    }
                }
            }

            // Extract authored model source (json_model -> modelLocation)
            Map<String, Object> modelObj = asMap(renderer.get("model"));
            if (modelObj != null) {
                Map<String, Object> src = asMap(modelObj.get("source"));
                if (src != null) {
                    Map<String, Object> srcData = asMap(src.get("data"));
                    if (srcData != null) {
                        modelLocation = readNullableString(srcData, "modelLocation", null);
                    }
                }
            }
            if (renderMode == RenderMode.MODEL || modelLocation != null) {
                renderMode = RenderMode.MODEL;
                String loc = modelLocation != null ? modelLocation : "minecraft:block/cube_all";
                authoredModel = StaticMeshModel.loadMinecraftJsonModel(loc, bridge);
            }
        }

        return new FxObjectSpec(name, objType, transform, renderMode, localSpace,
                durationTicks / 20f, looping, delayTicks / 20f,
                emissionRate, burstCount, lifeTicks / 20f, startSpeed, startSize,
                r, g, b, a, textureId, blendMode, depthTest, depthMask, cull,
                modelLocation, authoredModel);
    }

    private static TransformSpec parseTransform(@Nullable Map<String, Object> t) {
        if (t == null) return new TransformSpec("", null, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        String id = readString(t, "id", "");
        String parent = readNullableString(t, "_parentId", null);
        if ("_NULL_".equals(parent)) parent = null;
        float[] pos = readFloatList(t.get("localPosition"), 0, 0, 0);
        float[] rot = readFloatList4(t.get("localRotation"), 0, 0, 0, 1);
        float[] scl = readFloatList(t.get("localScale"), 1, 1, 1);
        return new TransformSpec(id, parent, pos[0], pos[1], pos[2], rot[0], rot[1], rot[2], rot[3], scl[0], scl[1], scl[2]);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(@Nullable Object obj) {
        return obj instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static float readNumberOrConstant(@Nullable Object obj, float fallback) {
        if (obj instanceof Number n) return n.floatValue();
        Map<String, Object> m = asMap(obj);
        if (m != null) {
            if (m.get("number") instanceof Number n) return n.floatValue();
            Map<String, Object> d = asMap(m.get("data"));
            if (d != null && d.get("number") instanceof Number n) return n.floatValue();
        }
        return fallback;
    }

    private static float[] readFloatList(@Nullable Object obj, float dx, float dy, float dz) {
        if (obj instanceof List<?> list && list.size() >= 3) {
            return new float[] {
                    ((Number) list.get(0)).floatValue(),
                    ((Number) list.get(1)).floatValue(),
                    ((Number) list.get(2)).floatValue()
            };
        }
        return new float[] {dx, dy, dz};
    }

    private static float[] readFloatList4(@Nullable Object obj, float dx, float dy, float dz, float dw) {
        if (obj instanceof List<?> list && list.size() >= 4) {
            return new float[] {
                    ((Number) list.get(0)).floatValue(),
                    ((Number) list.get(1)).floatValue(),
                    ((Number) list.get(2)).floatValue(),
                    ((Number) list.get(3)).floatValue()
            };
        }
        return new float[] {dx, dy, dz, dw};
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> readRootCompound(byte[] bytes) throws IOException {
        boolean gzip = bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        try (InputStream raw = new ByteArrayInputStream(bytes);
             InputStream decoded = gzip ? new GZIPInputStream(raw) : raw;
             DataInputStream in = new DataInputStream(new BufferedInputStream(decoded))) {
            int tagType = in.readUnsignedByte();
            if (tagType != 10) return null;
            in.readUTF();
            return (Map<String, Object>) readPayload(in, 10, 0);
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nesting too deep");
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int len = in.readInt();
                byte[] arr = new byte[Math.max(0, len)];
                in.readFully(arr);
                yield arr;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int elemType = in.readUnsignedByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, Math.min(len, 8192)));
                for (int i = 0; i < len; i++) list.add(readPayload(in, elemType, depth + 1));
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == 0) break;
                    String key = in.readUTF();
                    map.put(key, readPayload(in, childType, depth + 1));
                }
                yield map;
            }
            case 11 -> {
                int len = in.readInt();
                int[] arr = new int[Math.max(0, len)];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                yield arr;
            }
            case 12 -> {
                int len = in.readInt();
                long[] arr = new long[Math.max(0, len)];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                yield arr;
            }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        };
    }

    private static float readFloat(Map<String, Object> map, String key, float fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.floatValue() : fallback;
    }

    private static int readInt(Map<String, Object> map, String key, int fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static boolean readBool(Map<String, Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof Boolean b) return b;
        return fallback;
    }

    private static String readString(Map<String, Object> map, String key, String fallback) {
        Object v = map.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static @Nullable String readNullableString(Map<String, Object> map, String key, @Nullable String fallback) {
        Object v = map.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }
}
