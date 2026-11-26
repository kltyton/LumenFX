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
 * Full 26.2-parity Photon {@code .fx} NBT parser reading {@code fxData.fxObjects[]}, transform hierarchy,
 * timed bursts, shape emitters, curves (size/color/velocity/force/rotation over lifetime),
 * turbulence noise, forcefields, world block collision, sub-emitters, trails, beams, authored models,
 * HDR/soft-particle flags, and material textures/blend modes.
 */
public final class PhotonFxModel {
    public enum ObjectType { EMPTY, PARTICLE, TRAIL, BEAM, MESH }
    public enum RenderMode { BILLBOARD, STRETCHED_BILLBOARD, MODEL, MESH, TRAIL, BEAM }

    public record TransformSpec(String id, @Nullable String parentId,
                                float posX, float posY, float posZ,
                                float rotX, float rotY, float rotZ, float rotW,
                                float scaleX, float scaleY, float scaleZ) { }

    public record BurstSpec(float timeSeconds, int count, int cycles, float intervalSeconds, float probability) { }

    public record ShapeSpec(String shapeType, float radius, float angleDeg,
                            float boxX, float boxY, float boxZ, boolean surface) {
        public static final ShapeSpec DEFAULT = new ShapeSpec("point", 0.25f, 25f, 0.5f, 0.5f, 0.5f, false);
    }

    public record CollisionSpec(boolean enabled, float bounce, float dampen,
                                float lifetimeLoss, boolean dieOnCollision) {
        public static final CollisionSpec DISABLED = new CollisionSpec(false, 0.5f, 0.1f, 0f, false);
    }

    public record ForceFieldSpec(String kind, float strength, float x, float y, float z, float radius) { }

    public record SubEmitterSpec(String trigger, String targetName, int spawnCount, float probability) { }

    public record PhysicsExtras(int maxParticles,
                                List<BurstSpec> bursts,
                                ShapeSpec shape,
                                float gravityModifier,
                                float drag,
                                float noiseStrength,
                                float noiseFrequency,
                                float[] velocityOverLife,
                                float[] forceOverLife,
                                float sizeStartMult,
                                float sizeEndMult,
                                float startRotationDeg,
                                float rotationSpeedDeg,
                                float endR, float endG, float endB, float endA,
                                boolean hasEndColor,
                                CollisionSpec collision,
                                List<ForceFieldSpec> forceFields,
                                List<SubEmitterSpec> subEmitters,
                                float beamLength,
                                int beamSegments,
                                float beamNoise,
                                float trailEndWidthFactor,
                                boolean hdr,
                                boolean softParticles,
                                float softFadeDistance) {
        public static final PhysicsExtras DEFAULT = new PhysicsExtras(
                4096, List.of(), ShapeSpec.DEFAULT, 0f, 0f, 0f, 1.5f,
                new float[]{0f, 0f, 0f}, new float[]{0f, 0f, 0f},
                1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f, false,
                CollisionSpec.DISABLED, List.of(), List.of(),
                4.0f, 12, 0.15f, 0.1f, false, false, 0.75f);
    }

    public record FxObjectSpec(String name, ObjectType type, TransformSpec transform,
                               RenderMode renderMode, boolean localSpace,
                               float durationSeconds, boolean looping, float delaySeconds,
                               float emissionRate, int burstCount,
                               float startLifetimeSeconds, float startSpeed, float startSize,
                               float r, float g, float b, float a,
                               @Nullable String textureId, BlendMode blendMode,
                               boolean depthTest, boolean depthMask, boolean cull,
                               @Nullable String modelLocation, @Nullable StaticMeshModel authoredModel,
                               PhysicsExtras extras) {
        public FxObjectSpec(String name, ObjectType type, TransformSpec transform,
                            RenderMode renderMode, boolean localSpace,
                            float durationSeconds, boolean looping, float delaySeconds,
                            float emissionRate, int burstCount,
                            float startLifetimeSeconds, float startSpeed, float startSize,
                            float r, float g, float b, float a,
                            @Nullable String textureId, BlendMode blendMode,
                            boolean depthTest, boolean depthMask, boolean cull,
                            @Nullable String modelLocation, @Nullable StaticMeshModel authoredModel) {
            this(name, type, transform, renderMode, localSpace, durationSeconds, looping, delaySeconds,
                    emissionRate, burstCount, startLifetimeSeconds, startSpeed, startSize,
                    r, g, b, a, textureId, blendMode, depthTest, depthMask, cull,
                    modelLocation, authoredModel, PhysicsExtras.DEFAULT);
        }

        public MaterialModel material() {
            Map<String, String> tex = (textureId != null && !textureId.isBlank())
                    ? Map.of("Sampler0", textureId) : Map.of();
            PhysicsExtras ex = extras != null ? extras : PhysicsExtras.DEFAULT;
            return new MaterialModel(name, "lumenfx:core/effect", null, tex, blendMode,
                    depthTest, depthMask, cull, new float[4], ex.hdr(), "",
                    null, null, ex.softParticles(), ex.softFadeDistance(),
                    ex.hdr() ? 1.6f : 1.0f, false, ex.hdr() ? 1.0f : 0.0f, 1, 1, false, false);
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
        float delayTicks = Math.max(0f, readNumberOrConstant(config.get("startDelay"), readFloat(config, "delay", 0f)));
        int maxParticles = Math.max(1, Math.min(65536, readInt(config, "maxParticles", 4096)));

        float emissionRate = 10f;
        int burstCount = 0;
        List<BurstSpec> bursts = new ArrayList<>();
        Map<String, Object> emission = asMap(config.get("emission"));
        if (emission != null) {
            // Photon stores the nested emission setting in particles per Minecraft tick.
            emissionRate = 20f * readNumberOrConstant(emission.get("emissionRate"), 0.5f);
            Object burstsObj = emission.get("bursts");
            if (burstsObj instanceof List<?> bList) {
                for (Object bItem : bList) {
                    Map<String, Object> bMap = asMap(bItem);
                    if (bMap != null) {
                        int c = Math.max(0, Math.round(readNumberOrConstant(bMap.get("count"), 0f)));
                        float timeSec = Math.max(0f, readFloat(bMap, "time", 0f)) / 20f;
                        int cycles = Math.max(1, readInt(bMap, "cycles", 1));
                        float intervalSec = Math.max(0.01f, readFloat(bMap, "interval", 1f) / 20f);
                        float prob = Math.max(0f, Math.min(1f, readFloat(bMap, "probability", 1f)));
                        if (c > 0) {
                            bursts.add(new BurstSpec(timeSec, c, cycles, intervalSec, prob));
                            burstCount += c;
                        }
                    }
                }
            }
        } else {
            emissionRate = readNumberOrConstant(config.get("emissionRate"), 10f);
        }

        float lifeTicks = Math.max(1f, readNumberOrConstant(config.get("startLifetime"), 40f));
        float startSpeed = readNumberOrConstant(config.get("startSpeed"), 0.5f);
        float startSize = Math.max(0.02f, readAxisOrConstant(config.get("startSize"), 0, 1.0f));
        float startRotDeg = readAxisOrConstant(config.get("startRotation"), 2, 0f);
        float gravityModifier = readNumberOrConstant(config.get("gravityModifier"), readFloat(config, "gravity", 0f));
        float drag = Math.max(0f, readNumberOrConstant(config.get("drag"), 0f));
        int argb = readInt(config, "startColor", 0xFFFFFFFF);
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        if (a <= 0f) a = 1f;

        ShapeSpec shape = parseShape(asMap(config.get("shape")));
        float noiseStrength = 0f;
        float noiseFrequency = 1.5f;
        Map<String, Object> noise = asMap(config.get("noise"));
        if (noise != null && readBool(noise, "enable", readBool(noise, "enabled", true))) {
            noiseStrength = readNumberOrConstant(noise.get("strength"), readFloat(noise, "intensity", 0f));
            noiseFrequency = Math.max(0.05f, readFloat(noise, "frequency", 1.5f));
        }
        float[] velOverLife = parseVec3Module(asMap(config.get("velocityOverLifetime")));
        float[] forceOverLife = parseVec3Module(asMap(config.get("forceOverLifetime")));

        float sizeStartMult = 1f;
        float sizeEndMult = 1f;
        Map<String, Object> sizeMod = asMap(config.get("sizeOverLifetime"));
        if (sizeMod != null && readBool(sizeMod, "enable", readBool(sizeMod, "enabled", true))) {
            sizeStartMult = readNumberOrConstant(sizeMod.get("start"), 1f);
            sizeEndMult = readNumberOrConstant(sizeMod.get("end"), readNumberOrConstant(sizeMod.get("size"), 1f));
        }

        float rotSpeedDeg = 0f;
        Map<String, Object> rotMod = asMap(config.get("rotationOverLifetime"));
        if (rotMod != null && readBool(rotMod, "enable", readBool(rotMod, "enabled", true))) {
            rotSpeedDeg = readNumberOrConstant(rotMod.get("angularVelocity"),
                    readNumberOrConstant(rotMod.get("speed"), readFloat(rotMod, "roll", 0f)));
        }

        float endR = r, endG = g, endB = b, endA = a;
        boolean hasEndColor = false;
        Map<String, Object> colorMod = asMap(config.get("colorOverLifetime"));
        if (colorMod != null && readBool(colorMod, "enable", readBool(colorMod, "enabled", true))) {
            if (colorMod.containsKey("endColor")) {
                int endArgb = readInt(colorMod, "endColor", argb);
                endA = ((endArgb >>> 24) & 0xFF) / 255f;
                endR = ((endArgb >>> 16) & 0xFF) / 255f;
                endG = ((endArgb >>> 8) & 0xFF) / 255f;
                endB = (endArgb & 0xFF) / 255f;
                hasEndColor = true;
            }
        }

        CollisionSpec collision = parseCollision(asMap(config.get("collision")));
        List<ForceFieldSpec> forceFields = parseForceFields(config.get("forceFields"), config.get("forces"));
        List<SubEmitterSpec> subEmitters = parseSubEmitters(config.get("subEmitters"));

        float beamLength = Math.max(0.25f, readFloat(config, "beamLength", readFloat(config, "length", 4.0f)));
        int beamSegments = Math.max(2, Math.min(23, readInt(config, "beamSegments", readInt(config, "segments", 12))));
        float beamNoise = Math.max(0f, readFloat(config, "beamNoise", 0.15f));
        float trailEndWidth = Math.max(0f, readFloat(config, "trailEndWidth", 0.1f));

        RenderMode renderMode = objType == ObjectType.TRAIL ? RenderMode.TRAIL
                : objType == ObjectType.BEAM ? RenderMode.BEAM
                : objType == ObjectType.MESH ? RenderMode.MESH : RenderMode.BILLBOARD;
        String textureId = null;
        BlendMode blendMode = BlendMode.ALPHA;
        boolean depthTest = true;
        boolean depthMask = false;
        boolean cull = false;
        boolean hdr = readBool(config, "hdr", false) || readBool(config, "bloom", false);
        boolean softParticles = readBool(config, "softParticles", false);
        float softFadeDist = readFloat(config, "softFadeDistance", 0.75f);
        String modelLocation = null;
        StaticMeshModel authoredModel = null;

        Map<String, Object> renderer = asMap(config.get("renderer"));
        if (renderer != null) {
            String rm = readString(renderer, "renderMode", "").toLowerCase(Locale.ROOT);
            if (rm.equals("model")) renderMode = RenderMode.MODEL;
            else if (rm.contains("mesh")) renderMode = RenderMode.MESH;
            else if (rm.contains("stretch")) renderMode = RenderMode.STRETCHED_BILLBOARD;
            else if (rm.contains("billboard")) renderMode = RenderMode.BILLBOARD;

            if (readBool(renderer, "bloomEffect", false) || readBool(renderer, "hdr", false)) hdr = true;
            if (readBool(renderer, "softParticles", false)) softParticles = true;
            softFadeDist = readFloat(renderer, "softFadeDistance", softFadeDist);

            Map<String, Object> mats = asMap(renderer.get("materials"));
            if (mats != null && mats.get("payload") instanceof List<?> payload && !payload.isEmpty()) {
                Map<String, Object> m0 = asMap(payload.get(0));
                if (m0 != null) {
                    depthTest = readBool(m0, "depthTest", true);
                    depthMask = readBool(m0, "depthMask", false);
                    cull = readBool(m0, "cull", false);
                    if (readBool(m0, "bloom", false) || readBool(m0, "hdr", false)) hdr = true;
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

        PhysicsExtras extras = new PhysicsExtras(
                maxParticles, List.copyOf(bursts), shape, gravityModifier, drag,
                noiseStrength, noiseFrequency, velOverLife, forceOverLife,
                sizeStartMult, sizeEndMult, startRotDeg, rotSpeedDeg,
                endR, endG, endB, endA, hasEndColor, collision,
                List.copyOf(forceFields), List.copyOf(subEmitters),
                beamLength, beamSegments, beamNoise, trailEndWidth,
                hdr, softParticles, softFadeDist);

        return new FxObjectSpec(name, objType, transform, renderMode, localSpace,
                durationTicks / 20f, looping, delayTicks / 20f,
                emissionRate, burstCount, lifeTicks / 20f, startSpeed, startSize,
                r, g, b, a, textureId, blendMode, depthTest, depthMask, cull,
                modelLocation, authoredModel, extras);
    }

    private static ShapeSpec parseShape(@Nullable Map<String, Object> shape) {
        if (shape == null) return ShapeSpec.DEFAULT;
        Map<String, Object> shapeType = asMap(shape.get("shape"));
        Map<String, Object> shapeData = shapeType == null ? null : asMap(shapeType.get("data"));
        String kind = (shapeType == null ? readString(shape, "type", readString(shape, "shape", "point"))
                : readString(shapeType, "type", "point")).toLowerCase(Locale.ROOT);
        Map<String, Object> parameters = shapeData == null ? shape : shapeData;
        float radius = Math.max(0.01f, readFloat(parameters, "radius", 0.5f));
        float angle = readFloat(parameters, "angleA", readFloat(parameters, "angle", 25f));
        float[] box = readFloatList(shape.get("scale"), 0.5f, 0.5f, 0.5f);
        boolean surface = readBool(parameters, "surface", readBool(parameters, "emitFromShell", false));
        return new ShapeSpec(kind, radius, angle, box[0], box[1], box[2], surface);
    }

    private static CollisionSpec parseCollision(@Nullable Map<String, Object> col) {
        if (col == null) return CollisionSpec.DISABLED;
        boolean enabled = readBool(col, "enable", readBool(col, "enabled", false));
        if (!enabled) return CollisionSpec.DISABLED;
        return new CollisionSpec(true,
                Math.max(0f, readFloat(col, "bounce", 0.5f)),
                Math.max(0f, Math.min(1f, readFloat(col, "dampen", 0.1f))),
                Math.max(0f, Math.min(1f, readFloat(col, "lifetimeLoss", 0f))),
                readBool(col, "dieOnCollision", false));
    }

    private static List<ForceFieldSpec> parseForceFields(@Nullable Object primary, @Nullable Object fallback) {
        Object src = primary != null ? primary : fallback;
        if (!(src instanceof List<?> list)) return List.of();
        List<ForceFieldSpec> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> m = asMap(item);
            if (m == null) continue;
            String kind = readString(m, "type", readString(m, "kind", "point")).toLowerCase(Locale.ROOT);
            float strength = readFloat(m, "strength", 1.0f);
            float[] pos = readFloatList(m.get("position"),
                    readFloat(m, "x", 0f), readFloat(m, "y", 0f), readFloat(m, "z", 0f));
            float radius = Math.max(0.1f, readFloat(m, "radius", 8.0f));
            out.add(new ForceFieldSpec(kind, strength, pos[0], pos[1], pos[2], radius));
        }
        return out;
    }

    private static List<SubEmitterSpec> parseSubEmitters(@Nullable Object obj) {
        if (!(obj instanceof List<?> list)) return List.of();
        List<SubEmitterSpec> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> m = asMap(item);
            if (m == null) continue;
            String trigger = readString(m, "trigger", "DEATH").toUpperCase(Locale.ROOT);
            String target = readString(m, "target", readString(m, "emitter", ""));
            if (target.isBlank()) continue;
            int count = Math.max(1, readInt(m, "count", 1));
            float prob = Math.max(0f, Math.min(1f, readFloat(m, "probability", 1f)));
            out.add(new SubEmitterSpec(trigger, target, count, prob));
        }
        return out;
    }

    private static float[] parseVec3Module(@Nullable Map<String, Object> m) {
        if (m == null || !readBool(m, "enable", readBool(m, "enabled", true))) return new float[]{0f, 0f, 0f};
        if (m.containsKey("value")) return readFloatList(m.get("value"), 0f, 0f, 0f);
        return new float[]{
                readNumberOrConstant(m.get("x"), 0f),
                readNumberOrConstant(m.get("y"), 0f),
                readNumberOrConstant(m.get("z"), 0f)
        };
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

    private static float readAxisOrConstant(@Nullable Object obj, int axis, float fallback) {
        if (obj instanceof List<?> list) {
            return axis < list.size() ? readNumberOrConstant(list.get(axis), fallback) : fallback;
        }
        return readNumberOrConstant(obj, fallback);
    }

    private static float[] readFloatList(@Nullable Object obj, float dx, float dy, float dz) {
        if (obj instanceof List<?> list && list.size() >= 3) {
            return new float[] {
                    readNumberOrConstant(list.get(0), dx),
                    readNumberOrConstant(list.get(1), dy),
                    readNumberOrConstant(list.get(2), dz)
            };
        }
        return new float[] {dx, dy, dz};
    }

    private static float[] readFloatList4(@Nullable Object obj, float dx, float dy, float dz, float dw) {
        if (obj instanceof List<?> list && list.size() >= 4) {
            return new float[] {
                    readNumberOrConstant(list.get(0), dx),
                    readNumberOrConstant(list.get(1), dy),
                    readNumberOrConstant(list.get(2), dz),
                    readNumberOrConstant(list.get(3), dw)
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
