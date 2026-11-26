package com.kltyton.lumenfx.runtime.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Exact 26.2-compatible emitter model parsed from {@code format_version: 1} JSON
 * ({@code EffectEmitterJson} + {@code UnityEmitterJson} fields).
 */
public final class UnityEmitterModel {
    public enum RendererKind { SPRITE, RIBBON, MESH, LIGHT, DECAL, VOLUME, SPLAT, SIMULATION }
    public enum IntegratorKind { ANALYTIC, NIAGARA }
    public enum ShapeType { POINT, BOX, BOX_EDGE, SPHERE, HEMISPHERE, CONE, CYLINDER, CIRCLE, EDGE, DONUT, MESH }

    public record BurstSpec(double time, int minCount, int maxCount, @Nullable ScalarCurve countCurve,
                            int cycles, double interval, double probability) { }

    public record UnityShapeSpec(double posX, double posY, double posZ,
                                 float rotX, float rotY, float rotZ, float rotW,
                                 double radiusThickness, double randomDirection, double sphericalDirection,
                                 double randomPosition,
                                 double boxThickX, double boxThickY, double boxThickZ,
                                 double arcRadians, double donutRing, double donutTube,
                                 boolean alignToDirection) { }

    public record ShapeSpec(ShapeType type, double sizeX, double sizeY, double sizeZ,
                            boolean surface, double angle, @Nullable UnityShapeSpec unity) { }

    public record MeshEntrySpec(String assetId, @Nullable StaticMeshModel mesh,
                                float scaleX, float scaleY, float scaleZ,
                                float rotX, float rotY, float rotZ, float rotW,
                                float pivotX, float pivotY, float pivotZ) { }

    public record TrailSpec(boolean enabled, float ratio, float minVertexDistance, boolean worldSpace,
                            boolean dieWithParticles, boolean sizeAffectsWidth, boolean inheritColor,
                            ScalarCurve lifetime, ScalarCurve widthOverTrail,
                            ColorCurve colorOverTrail, ColorCurve colorOverLifetime) {
        public static final TrailSpec DISABLED = new TrailSpec(false, 1f, 0.05f, true, true, true, true,
                ScalarCurve.constant(0.5f), ScalarCurve.constant(1f), ColorCurve.white(), ColorCurve.white());
    }

    public record CollisionSpec(boolean enabled, float bounce, float dampen, float lifetimeLoss,
                                double colliderForce, boolean multiplyByAngle, boolean multiplyBySpeed,
                                boolean multiplyBySize, double maxSpeed, double maxSize) {
        public static final CollisionSpec DISABLED = new CollisionSpec(false, 0.5f, 0.2f, 0f, 0.0, true, false, false, 1000.0, 100.0);
    }

    public record SubEmitterSpec(String targetEmitter, String eventType, boolean inheritColor,
                                 boolean inheritSize, boolean inheritRotation, boolean inheritLifetime,
                                 double emitProbability) { }

    private final String name;
    private final RendererKind renderer;
    private final String materialName;
    private final MaterialModel material;
    private final IntegratorKind integrator;
    private final boolean localSpace;
    private final double delay;
    private final double duration;
    private final boolean loop;
    private final int maxParticles;
    private final ScalarCurve rate;
    private final @Nullable ScalarCurve rateOverDistance;
    private final double distanceSpacing;
    private final List<BurstSpec> bursts;
    private final ScalarCurve lifetime;
    private final ScalarCurve width;
    private final ScalarCurve height;
    private final ScalarCurve rotation;
    private final ScalarCurve angularVelocity;
    private final ScalarCurve startSpeed;
    private final double[] startDirection;
    private final ColorCurve startColor;
    private final double[] velocityMin;
    private final double[] velocityMax;
    private final double[] acceleration;
    private final ScalarCurve drag;
    private final ScalarCurve[] velocityOverLife;
    private final ScalarCurve[] forceOverLife;
    private final ScalarCurve sizeOverLife;
    private final ColorCurve colorOverLife;
    private final ShapeSpec shape;
    private final double[] noiseStrength;
    private final double noiseFrequency;
    private final int atlasColumns;
    private final int atlasRows;
    private final ScalarCurve atlasFrame;
    private final @Nullable MeshEntrySpec meshEntry;
    private final float[] tint;
    private final boolean prewarm;
    private final @Nullable Long randomSeed;
    private final double simulationSpeed;
    private final double maximumParticleDeltaTime;
    private final boolean stretchedBillboard;
    private final float velocityStretchScale;
    private final TrailSpec trail;
    private final CollisionSpec collision;
    private final List<SubEmitterSpec> subEmitters;
    private final MaterialDataCurves materialData;

    public UnityEmitterModel(
            String name, RendererKind renderer, String materialName, MaterialModel material,
            IntegratorKind integrator, boolean localSpace, double delay, double duration, boolean loop,
            int maxParticles, ScalarCurve rate, @Nullable ScalarCurve rateOverDistance, double distanceSpacing,
            List<BurstSpec> bursts, ScalarCurve lifetime, ScalarCurve width, ScalarCurve height,
            ScalarCurve rotation, ScalarCurve angularVelocity, ScalarCurve startSpeed, double[] startDirection,
            ColorCurve startColor, double[] velocityMin, double[] velocityMax, double[] acceleration,
            ScalarCurve drag, ScalarCurve[] velocityOverLife, ScalarCurve[] forceOverLife,
            ScalarCurve sizeOverLife, ColorCurve colorOverLife, ShapeSpec shape,
            double[] noiseStrength, double noiseFrequency, int atlasColumns, int atlasRows, ScalarCurve atlasFrame,
            @Nullable MeshEntrySpec meshEntry, float[] tint, boolean prewarm, @Nullable Long randomSeed,
            double simulationSpeed, double maximumParticleDeltaTime,
            boolean stretchedBillboard, float velocityStretchScale,
            TrailSpec trail, CollisionSpec collision, List<SubEmitterSpec> subEmitters, MaterialDataCurves materialData) {
        this.name = name;
        this.renderer = renderer;
        this.materialName = materialName;
        this.material = material;
        this.integrator = integrator;
        this.localSpace = localSpace;
        this.delay = delay;
        this.duration = duration;
        this.loop = loop;
        this.maxParticles = maxParticles;
        this.rate = rate;
        this.rateOverDistance = rateOverDistance;
        this.distanceSpacing = distanceSpacing;
        this.bursts = List.copyOf(bursts);
        this.lifetime = lifetime;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.angularVelocity = angularVelocity;
        this.startSpeed = startSpeed;
        this.startDirection = startDirection;
        this.startColor = startColor;
        this.velocityMin = velocityMin;
        this.velocityMax = velocityMax;
        this.acceleration = acceleration;
        this.drag = drag;
        this.velocityOverLife = velocityOverLife;
        this.forceOverLife = forceOverLife;
        this.sizeOverLife = sizeOverLife;
        this.colorOverLife = colorOverLife;
        this.shape = shape;
        this.noiseStrength = noiseStrength;
        this.noiseFrequency = noiseFrequency;
        this.atlasColumns = atlasColumns;
        this.atlasRows = atlasRows;
        this.atlasFrame = atlasFrame;
        this.meshEntry = meshEntry;
        this.tint = tint;
        this.prewarm = prewarm;
        this.randomSeed = randomSeed;
        this.simulationSpeed = simulationSpeed;
        this.maximumParticleDeltaTime = maximumParticleDeltaTime;
        this.stretchedBillboard = stretchedBillboard;
        this.velocityStretchScale = velocityStretchScale;
        this.trail = trail;
        this.collision = collision;
        this.subEmitters = List.copyOf(subEmitters);
        this.materialData = materialData;
    }

    public String name() { return name; }
    public MaterialDataCurves materialData() { return materialData; }
    public RendererKind renderer() { return renderer; }
    public String materialName() { return materialName; }
    public MaterialModel material() { return material; }
    public IntegratorKind integrator() { return integrator; }
    public boolean localSpace() { return localSpace; }
    public double delay() { return delay; }
    public double duration() { return duration; }
    public boolean loop() { return loop; }
    public int maxParticles() { return maxParticles; }
    public ScalarCurve rate() { return rate; }
    public @Nullable ScalarCurve rateOverDistance() { return rateOverDistance; }
    public double distanceSpacing() { return distanceSpacing; }
    public List<BurstSpec> bursts() { return bursts; }
    public ScalarCurve lifetime() { return lifetime; }
    public ScalarCurve width() { return width; }
    public ScalarCurve height() { return height; }
    public ScalarCurve rotation() { return rotation; }
    public ScalarCurve angularVelocity() { return angularVelocity; }
    public ScalarCurve startSpeed() { return startSpeed; }
    public double[] startDirection() { return startDirection; }
    public ColorCurve startColor() { return startColor; }
    public double[] velocityMin() { return velocityMin; }
    public double[] velocityMax() { return velocityMax; }
    public double[] acceleration() { return acceleration; }
    public ScalarCurve drag() { return drag; }
    public ScalarCurve[] velocityOverLife() { return velocityOverLife; }
    public ScalarCurve[] forceOverLife() { return forceOverLife; }
    public ScalarCurve sizeOverLife() { return sizeOverLife; }
    public ColorCurve colorOverLife() { return colorOverLife; }
    public ShapeSpec shape() { return shape; }
    public double[] noiseStrength() { return noiseStrength; }
    public double noiseFrequency() { return noiseFrequency; }
    public int atlasColumns() { return atlasColumns; }
    public int atlasRows() { return atlasRows; }
    public ScalarCurve atlasFrame() { return atlasFrame; }
    public @Nullable MeshEntrySpec meshEntry() { return meshEntry; }
    public float[] tint() { return tint; }
    public boolean prewarm() { return prewarm; }
    public @Nullable Long randomSeed() { return randomSeed; }
    public double simulationSpeed() { return simulationSpeed; }
    public double maximumParticleDeltaTime() { return maximumParticleDeltaTime; }
    public boolean stretchedBillboard() { return stretchedBillboard; }
    public float velocityStretchScale() { return velocityStretchScale; }
    public TrailSpec trail() { return trail; }
    public CollisionSpec collision() { return collision; }
    public List<SubEmitterSpec> subEmitters() { return subEmitters; }
    public @Nullable String primaryTexture() { return material.primaryTexture(); }

    public static UnityEmitterModel fromJson(JsonObject value, Map<String, MaterialModel> materials,
                                             @Nullable McResourceBridge bridge, String fallbackName) {
        String name = readString(value, "name", fallbackName);
        RendererKind renderer = parseRendererKind(readString(value, "renderer", "SPRITE"));
        String materialName = readString(value, "material", "");
        MaterialModel material = materials.getOrDefault(materialName, MaterialModel.defaultMaterial(materialName));
        IntegratorKind integrator = readString(value, "integrator", "analytic").equalsIgnoreCase("niagara")
                ? IntegratorKind.NIAGARA : IntegratorKind.ANALYTIC;

        // Support BOTH 26.2 production schema (top-level loop/rate/bursts/lifetime/width/height)
        // AND nested Shuriken JSON (main/emission) seamlessly!
        JsonObject mainObj = optionalObj(value, "main");
        JsonObject emissionObj = optionalObj(value, "emission");

        boolean localSpace = readBool(value, "local_space",
                mainObj != null && !readString(mainObj, "simulation_space", "local").equalsIgnoreCase("world"));
        double delay = readDouble(value, "delay",
                mainObj != null ? ScalarCurve.fromJson(mainObj.get("start_delay"), 0f).evaluate(0f, 0f) : 0.0);
        double duration = Math.max(0.01, readDouble(value, "duration",
                mainObj != null ? readDouble(mainObj, "duration", 1.0) : 1.0));
        boolean loop = value.has("loop") ? readBool(value, "loop", true)
                : (mainObj != null ? readBool(mainObj, "looping", true) : readBool(value, "looping", true));
        int maxParticles = Math.max(1, readInt(value, "max_particles",
                mainObj != null ? readInt(mainObj, "max_particles", 4096) : 4096));

        ScalarCurve rate;
        if (value.has("rate")) {
            rate = ScalarCurve.fromJson(value.get("rate"), 0f);
        } else if (emissionObj != null) {
            boolean emEnabled = readBool(emissionObj, "enabled", true);
            rate = emEnabled ? ScalarCurve.fromJson(firstOf(emissionObj, "rate_over_time", "rateOverTime"), 0f) : ScalarCurve.constant(0f);
        } else {
            rate = ScalarCurve.constant(0f);
        }

        ScalarCurve rateOverDistance = null;
        if (value.has("rate_over_distance") && !value.get("rate_over_distance").isJsonNull()) {
            rateOverDistance = ScalarCurve.fromJson(value.get("rate_over_distance"), 0f);
        } else if (emissionObj != null && emissionObj.has("rate_over_distance")) {
            rateOverDistance = ScalarCurve.fromJson(emissionObj.get("rate_over_distance"), 0f);
        }
        double distanceSpacing = readDouble(value, "distance_spacing", 0.0);

        List<BurstSpec> bursts = new ArrayList<>();
        JsonArray burstArr = value.has("bursts") && value.get("bursts").isJsonArray() ? value.getAsJsonArray("bursts")
                : (emissionObj != null && emissionObj.has("bursts") && emissionObj.get("bursts").isJsonArray()
                        ? emissionObj.getAsJsonArray("bursts") : null);
        if (burstArr != null) {
            for (JsonElement el : burstArr) {
                if (!el.isJsonObject()) continue;
                JsonObject b = el.getAsJsonObject();
                double time = Math.max(0.0, readDouble(b, "time", 0.0));
                int cycles = Math.max(1, readInt(b, "cycles", readInt(b, "cycle_count", 1)));
                double interval = Math.max(0.0, readDouble(b, "interval", readDouble(b, "repeat_interval", 0.0)));
                double prob = Math.max(0.0, Math.min(1.0, readDouble(b, "probability", 1.0)));
                if (b.has("count") && !b.get("count").isJsonNull()) {
                    ScalarCurve c = ScalarCurve.fromJson(b.get("count"), 0f);
                    int lo = Math.round(c.evaluateMin(0f));
                    int hi = Math.round(c.evaluateMax(0f));
                    bursts.add(new BurstSpec(time, lo, hi, c, cycles, interval, prob));
                } else {
                    int min = readInt(b, "min", readInt(b, "min_count", 0));
                    int max = readInt(b, "max", readInt(b, "max_count", min));
                    bursts.add(new BurstSpec(time, min, max, null, cycles, interval, prob));
                }
            }
        }

        ScalarCurve lifetime = ScalarCurve.fromJson(
                value.has("lifetime") ? value.get("lifetime")
                        : (mainObj != null ? firstOf(mainObj, "start_lifetime", "startLifetime") : null), 1.0f);
        ScalarCurve width = ScalarCurve.fromJson(
                value.has("width") ? value.get("width")
                        : (mainObj != null ? firstOf(mainObj, "start_size", "startSize") : null), 1.0f);
        ScalarCurve height = ScalarCurve.fromJson(value.has("height") ? value.get("height") : null, width.evaluate(0f, 0f));
        if (!value.has("height")) height = width;
        ScalarCurve rotation = ScalarCurve.fromJson(
                value.has("rotation") ? value.get("rotation")
                        : (mainObj != null ? firstOf(mainObj, "start_rotation", "startRotation") : null), 0f);
        ScalarCurve angularVelocity = ScalarCurve.fromJson(value.get("angular_velocity"), 0f);
        ScalarCurve startSpeed = ScalarCurve.fromJson(
                value.has("start_speed") ? value.get("start_speed")
                        : (mainObj != null ? firstOf(mainObj, "start_speed", "startSpeed") : null), 0f);
        double[] startDirection = readVec3(value.get("start_direction"), 0.0, 1.0, 0.0);
        ColorCurve startColor = ColorCurve.fromJson(
                value.has("start_color") ? value.get("start_color")
                        : (mainObj != null ? firstOf(mainObj, "start_color", "startColor") : null));
        double[] velMin = readVec3(value.get("velocity_min"), 0.0, 0.0, 0.0);
        double[] velMax = readVec3(value.get("velocity_max"), 0.0, 0.0, 0.0);
        double[] accel = readVec3(value.get("acceleration"), 0.0, 0.0, 0.0);
        if (!value.has("acceleration") && mainObj != null && mainObj.has("gravity_modifier")) {
            float g = ScalarCurve.fromJson(mainObj.get("gravity_modifier"), 0f).evaluate(0f, 0f);
            accel[1] = -9.81 * g;
        }
        ScalarCurve drag = ScalarCurve.fromJson(value.get("drag"), 0f);
        ScalarCurve[] velOverLife = readCurveVec3(value.get("velocity_over_life"));
        ScalarCurve[] forceOverLife = readCurveVec3(value.get("force_over_life"));
        ScalarCurve sizeOverLife = ScalarCurve.fromJson(value.get("size_over_life"), 1f);
        ColorCurve colorOverLife = ColorCurve.fromJson(value.get("color"));

        ShapeSpec shape = parseShape(optionalObj(value, "shape"));

        JsonObject noiseObj = optionalObj(value, "noise");
        double[] noiseStrength = noiseObj != null ? readVec3(noiseObj.get("strength"), 0.0, 0.0, 0.0) : new double[3];
        double noiseFrequency = noiseObj != null ? Math.max(0.01, readDouble(noiseObj, "frequency", 1.0)) : 1.0;

        int atlasCols = Math.max(1, readInt(value, "atlas_columns", 1));
        int atlasRows = Math.max(1, readInt(value, "atlas_rows", 1));
        ScalarCurve atlasFrame = ScalarCurve.fromJson(value.get("atlas_frame"), 0f);

        MeshEntrySpec meshEntry = parseMeshEntry(optionalObj(value, "mesh"), bridge);

        JsonObject ur = optionalObj(value, "unity_renderer");
        float[] tint = ur != null && ur.has("tint") ? ColorCurve.readRgba(ur.get("tint")) : new float[] {1f, 1f, 1f, 1f};
        boolean prewarm = ur != null && readBool(ur, "prewarm", false);
        Long randomSeed = (ur != null && ur.has("random_seed") && ur.get("random_seed").isJsonPrimitive())
                ? ur.get("random_seed").getAsLong() : null;
        double simSpeed = ur != null ? Math.max(0.0, readDouble(ur, "simulation_speed", 1.0)) : 1.0;
        double maxDt = ur != null ? readDouble(ur, "maximum_particle_delta_time", 0.05) : 0.05;

        boolean stretched = false;
        float stretchScale = 1.0f;
        if (ur != null && ur.has("billboard") && ur.get("billboard").isJsonObject()) {
            JsonObject bb = ur.getAsJsonObject("billboard");
            String mode = readString(bb, "mode", "Billboard").toLowerCase(Locale.ROOT);
            stretched = mode.contains("stretch");
            stretchScale = (float) readDouble(bb, "velocity_scale", readDouble(bb, "length_scale", 1.0));
        }

        TrailSpec trail = parseTrail(ur != null ? optionalObj(ur, "particle_trail") : null);
        CollisionSpec collision = parseCollision(value, ur);
        List<SubEmitterSpec> subEmitters = parseSubEmitters(ur != null ? optionalArr(ur, "sub_emitters") : null);

        return new UnityEmitterModel(
                name, renderer, materialName, material, integrator, localSpace, delay, duration, loop,
                maxParticles, rate, rateOverDistance, distanceSpacing, bursts,
                lifetime, width, height, rotation, angularVelocity, startSpeed, startDirection,
                startColor, velMin, velMax, accel, drag, velOverLife, forceOverLife,
                sizeOverLife, colorOverLife, shape, noiseStrength, noiseFrequency,
                atlasCols, atlasRows, atlasFrame, meshEntry, tint, prewarm, randomSeed,
                simSpeed, maxDt, stretched, stretchScale, trail, collision, subEmitters,
                MaterialDataCurves.fromJson(value.get("custom_data")));
    }

    private static RendererKind parseRendererKind(String raw) {
        try {
            return RendererKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return RendererKind.SPRITE;
        }
    }

    private static ShapeSpec parseShape(@Nullable JsonObject s) {
        if (s == null) return new ShapeSpec(ShapeType.POINT, 0, 0, 0, false, 0, null);
        String tStr = readString(s, "type", readString(s, "shape_type", "point")).toUpperCase(Locale.ROOT);
        ShapeType type;
        try {
            type = ShapeType.valueOf(tStr);
        } catch (IllegalArgumentException ignored) {
            if (tStr.contains("BOX_EDGE")) type = ShapeType.BOX_EDGE;
            else if (tStr.contains("BOX")) type = ShapeType.BOX;
            else if (tStr.contains("CONE")) type = ShapeType.CONE;
            else if (tStr.contains("HEMI")) type = ShapeType.HEMISPHERE;
            else if (tStr.contains("SPHERE")) type = ShapeType.SPHERE;
            else if (tStr.contains("CIRCLE")) type = ShapeType.CIRCLE;
            else if (tStr.contains("EDGE")) type = ShapeType.EDGE;
            else if (tStr.contains("DONUT")) type = ShapeType.DONUT;
            else if (tStr.contains("CYL")) type = ShapeType.CYLINDER;
            else type = ShapeType.POINT;
        }
        double[] size = readVec3(s.get("size"), 0, 0, 0);
        if (!s.has("size") && s.has("radius")) {
            double r = readDouble(s, "radius", 0.5);
            size = new double[] {r, r, r};
        }
        boolean surface = readBool(s, "surface", false);
        double angle = readDouble(s, "angle", 0.0);
        UnityShapeSpec unity = null;
        JsonObject u = optionalObj(s, "unity");
        if (u != null) {
            double[] pos = readVec3(u.get("position"), 0, 0, 0);
            float[] rot = readQuat(u.get("rotation"));
            double[] bt = readVec3(u.get("box_thickness"), 0, 0, 0);
            JsonObject arc = optionalObj(u, "arc");
            JsonObject donut = optionalObj(u, "donut");
            unity = new UnityShapeSpec(
                    pos[0], pos[1], pos[2], rot[0], rot[1], rot[2], rot[3],
                    readDouble(u, "radius_thickness", 1.0),
                    readDouble(u, "random_direction", 0.0),
                    readDouble(u, "spherical_direction", 0.0),
                    readDouble(u, "random_position", 0.0),
                    bt[0], bt[1], bt[2],
                    arc != null ? readDouble(arc, "radians", Math.PI * 2.0) : Math.PI * 2.0,
                    donut != null ? readDouble(donut, "ring", 0.5) : 0.5,
                    donut != null ? readDouble(donut, "tube", 0.1) : 0.1,
                    readBool(u, "align_to_direction", false));
        }
        return new ShapeSpec(type, size[0], size[1], size[2], surface, angle, unity);
    }

    private static @Nullable MeshEntrySpec parseMeshEntry(@Nullable JsonObject meshObj, @Nullable McResourceBridge bridge) {
        if (meshObj == null) return null;
        JsonArray entries = optionalArr(meshObj, "entries");
        if (entries == null || entries.size() == 0 || !entries.get(0).isJsonObject()) return null;
        JsonObject e0 = entries.get(0).getAsJsonObject();
        String asset = readString(e0, "asset", "");
        if (asset.isBlank()) return null;
        StaticMeshModel loaded = StaticMeshModel.loadLumenMesh(asset, bridge);
        double[] scale = readVec3(e0.get("scale"), 1, 1, 1);
        float[] rot = readQuat(e0.get("rotation"));
        double[] pivot = readVec3(e0.get("pivot"), 0, 0, 0);
        return new MeshEntrySpec(asset, loaded, (float) scale[0], (float) scale[1], (float) scale[2],
                rot[0], rot[1], rot[2], rot[3], (float) pivot[0], (float) pivot[1], (float) pivot[2]);
    }

    private static TrailSpec parseTrail(@Nullable JsonObject t) {
        if (t == null) return TrailSpec.DISABLED;
        return new TrailSpec(
                true,
                (float) readDouble(t, "ratio", 1.0),
                (float) readDouble(t, "minimum_vertex_distance", 0.05),
                readBool(t, "world_space", true),
                readBool(t, "die_with_particles", true),
                readBool(t, "size_affects_width", true),
                readBool(t, "inherit_color", true),
                ScalarCurve.fromJson(t.get("lifetime"), 0.5f),
                ScalarCurve.fromJson(t.get("width_over_trail"), 1.0f),
                ColorCurve.fromJson(t.get("color_over_trail")),
                ColorCurve.fromJson(t.get("color_over_lifetime")));
    }

    private static CollisionSpec parseCollision(JsonObject emitter, @Nullable JsonObject ur) {
        JsonObject col = optionalObj(emitter, "collision");
        if (col == null && ur != null) col = optionalObj(ur, "world_collision");
        if (col == null && ur != null) col = optionalObj(ur, "plane_collision");
        if (col == null) return CollisionSpec.DISABLED;
        if (col.has("enabled") && !readBool(col, "enabled", true)) return CollisionSpec.DISABLED;
        double force = readDouble(col, "collider_force", readDouble(col, "colliderForce", 0.0));
        return new CollisionSpec(
                true,
                (float) readDouble(col, "bounce", 0.5),
                (float) readDouble(col, "dampen", 0.2),
                (float) readDouble(col, "lifetime_loss", 0.0),
                force,
                readBool(col, "multiply_collider_force_by_collision_angle", true),
                readBool(col, "multiply_collider_force_by_particle_speed", false),
                readBool(col, "multiply_collider_force_by_particle_size", false),
                readDouble(col, "max_kill_speed", 1000.0), 100.0);
    }

    private static List<SubEmitterSpec> parseSubEmitters(@Nullable JsonArray arr) {
        if (arr == null) return List.of();
        List<SubEmitterSpec> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String target = readString(o, "emitter", readString(o, "target", ""));
            if (target.isBlank()) continue;
            out.add(new SubEmitterSpec(
                    target,
                    readString(o, "event", "birth").toLowerCase(Locale.ROOT),
                    readBool(o, "inherit_color", false),
                    readBool(o, "inherit_size", false),
                    readBool(o, "inherit_rotation", false),
                    readBool(o, "inherit_lifetime", false),
                    readDouble(o, "probability", 1.0)));
        }
        return List.copyOf(out);
    }

    private static ScalarCurve[] readCurveVec3(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return new ScalarCurve[] {ScalarCurve.constant(0), ScalarCurve.constant(0), ScalarCurve.constant(0)};
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            return new ScalarCurve[] {
                    arr.size() > 0 ? ScalarCurve.fromJson(arr.get(0), 0) : ScalarCurve.constant(0),
                    arr.size() > 1 ? ScalarCurve.fromJson(arr.get(1), 0) : ScalarCurve.constant(0),
                    arr.size() > 2 ? ScalarCurve.fromJson(arr.get(2), 0) : ScalarCurve.constant(0)
            };
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            return new ScalarCurve[] {
                    ScalarCurve.fromJson(o.get("x"), 0),
                    ScalarCurve.fromJson(o.get("y"), 0),
                    ScalarCurve.fromJson(o.get("z"), 0)
            };
        }
        return new ScalarCurve[] {ScalarCurve.constant(0), ScalarCurve.constant(0), ScalarCurve.constant(0)};
    }

    private static double[] readVec3(@Nullable JsonElement el, double dx, double dy, double dz) {
        if (el == null || el.isJsonNull()) return new double[] {dx, dy, dz};
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            return new double[] {
                    arr.size() > 0 ? arr.get(0).getAsDouble() : dx,
                    arr.size() > 1 ? arr.get(1).getAsDouble() : dy,
                    arr.size() > 2 ? arr.get(2).getAsDouble() : dz
            };
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            return new double[] {readDouble(o, "x", dx), readDouble(o, "y", dy), readDouble(o, "z", dz)};
        }
        return new double[] {dx, dy, dz};
    }

    private static float[] readQuat(@Nullable JsonElement el) {
        if (el != null && el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() == 4) {
                return new float[] {arr.get(0).getAsFloat(), arr.get(1).getAsFloat(),
                        arr.get(2).getAsFloat(), arr.get(3).getAsFloat()};
            }
        }
        return new float[] {0f, 0f, 0f, 1f};
    }

    private static @Nullable JsonObject optionalObj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static @Nullable JsonArray optionalArr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
    }

    private static @Nullable JsonElement firstOf(JsonObject o, String a, String b) {
        return o.has(a) ? o.get(a) : (o.has(b) ? o.get(b) : null);
    }

    private static String readString(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static boolean readBool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
    }

    private static int readInt(JsonObject o, String key, int fallback) {
        try { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static double readDouble(JsonObject o, String key, double fallback) {
        try {
            if (!o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
            double v = o.get(key).getAsDouble();
            return Double.isFinite(v) ? v : fallback;
        } catch (RuntimeException ignored) { return fallback; }
    }
}
