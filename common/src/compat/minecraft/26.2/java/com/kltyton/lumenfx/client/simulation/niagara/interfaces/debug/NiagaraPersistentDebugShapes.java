package com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.interfaces.debug.NiagaraPersistentDebugShape;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.List;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Persistent primitives resolve their script once and read its tags after each simulation step. */
public final class NiagaraPersistentDebugShapes {
    private static final String FLOAT = "/Script/Niagara.NiagaraFloat";
    private static final String INT = "/Script/Niagara.NiagaraInt32";
    private static final String BOOL = "/Script/Niagara.NiagaraBool";
    private static final String SPACE = "/Script/Niagara.ENiagaraCoordinateSpace";
    private static final String AXIS = "/Script/Niagara.ENiagaraOrientationAxis";
    private static final String VEC2 = "/Script/CoreUObject.Vector2f";
    private static final String VEC3 = "/Script/CoreUObject.Vector3f";
    private static final String QUAT = "/Script/CoreUObject.Quat4f";
    private static final String COLOR = "/Script/CoreUObject.LinearColor";
    private static final int SIMULATION = 0, WORLD = 1, LOCAL = 2;
    private static final Vector3f ZERO = new Vector3f(), ONE = new Vector3f(1);
    private static final Vector3f X_AXIS = new Vector3f(1, 0, 0), Y_AXIS = new Vector3f(0, 1, 0), Z_AXIS = new Vector3f(0, 0, 1);
    private static final Quaternionf IDENTITY = new Quaternionf();

    private final List<NiagaraPersistentDebugShape> candidates;
    private @Nullable List<NiagaraPersistentDebugShape> resolved;

    public NiagaraPersistentDebugShapes(List<NiagaraPersistentDebugShape> candidates) { this.candidates = candidates; }

    public void reset() { resolved = null; }

    void draw(NiagaraCpuDebugLines lines, NiagaraParameterStore overrides, Pose pose) {
        if (candidates.isEmpty()) return;
        if (resolved == null) resolved = candidates.stream().filter(shape -> valid(shape.function(), new Tags(shape, overrides))).toList();
        if (resolved.isEmpty()) return;
        Owner owner = new Owner(pose);
        for (var shape : resolved) {
            var target = lines.persistentBuffer(shape.interfaceId(), shape.lineLimit());
            if (!target.full()) draw(shape.function(), new Tags(shape, overrides), owner, target);
        }
    }

    private static boolean valid(String function, Tags tags) {
        if (function.equals("DrawLine")) return tags.has("StartLocation", VEC3) || tags.has("EndLocation", VEC3);
        if (tags.has("Center", VEC3)) return true;
        return switch (function) {
            case "DrawRectangle" -> tags.has("Extents", VEC2);
            case "DrawBox" -> tags.has("Extents", VEC3);
            case "DrawCircle", "DrawSphere", "DrawCylinder" -> tags.has("Radius", FLOAT);
            // The source calls this local value Radius, but it resolves parameter 4 (Angle).
            case "DrawCone" -> tags.has("Angle", FLOAT);
            case "DrawTorus" -> tags.has("MajorRadius", FLOAT) || tags.has("MinorRadius", FLOAT);
            default -> false;
        };
    }

    private static void draw(String function, Tags tags, Owner owner, NiagaraDebugGeometry.Lines lines) {
        if (function.equals("DrawLine")) {
            Vector3f start = owner.vector(tags, "StartLocation", "StartLocationCoordinateSpace", ZERO, SIMULATION, true);
            Vector3f end = owner.vector(tags, "EndLocation", "EndLocationCoordinateSpace", ZERO, SIMULATION, true);
            if (start.x != end.x || start.y != end.y || start.z != end.z) lines.line(start, end, tags.color(0xff0000ff));
            return;
        }
        boolean sphere = function.equals("DrawSphere");
        int centerSpace = switch (function) {
            case "DrawCylinder", "DrawCone", "DrawTorus" -> WORLD;
            default -> SIMULATION;
        };
        Vector3f center = owner.vector(tags, "Center", sphere ? "Center Coordinate Space" : "CenterCoordinateSpace", ZERO, centerSpace, true)
                .add(owner.vector(tags, sphere ? "Offset From Center" : "Offset",
                        sphere ? "Offset Coordinate Space" : "OffsetCoordinateSpace", ZERO, centerSpace, false));
        int color = tags.color(0x00ff00ff);
        switch (function) {
            case "DrawRectangle", "DrawCircle" -> plane(function, tags, owner, lines, center, color);
            case "DrawBox" -> {
                if (!tags.has("Extents", VEC3)) return;
                Vector3f axis = tags.vector("RotationAxis", VEC3, Z_AXIS);
                float angle = (float)Math.toRadians(tags.number("RotationNormalizedAngle", 0) * 360f);
                float sin = (float)Math.sin(angle * .5f);
                var rotation = new Quaternionf(axis.x * sin, axis.y * sin, axis.z * sin, (float)Math.cos(angle * .5f));
                var extent = tags.vector("Extents", VEC3, ONE).mul(tags.flag("HalfExtents", true) ? .5f : 1);
                owner.convert(center, tags.space(SIMULATION), WORLD, true);
                NiagaraDebugGeometry.box(lines, center, rotation, extent, owner.rotation(tags, "RotationCoordinateSpace"),
                        owner.scale(tags, "RotationCoordinateSpace"), color);
            }
            case "DrawSphere" -> {
                if (!tags.has("Radius", FLOAT)) return;
                Vector3f radii = tags.vector("NonUniform Scale", VEC3, ONE).mul(tags.number("Radius", 1));
                Quaternionf orientation = new Quaternionf().rotationTo(X_AXIS, tags.vector("Sphere Orientation Axis", VEC3, X_AXIS));
                orientation.mul(tags.quaternion("Additional Rotation"));
                owner.convert(center, tags.space(SIMULATION), WORLD, true);
                NiagaraDebugGeometry.sphere(lines, center, radii, orientation, owner.rotation(tags, "Radius Coordinate Space"),
                        owner.scale(tags, "Radius Coordinate Space"), tags.integer("Num Segments", 36),
                        tags.flag("Hemisphere X", true), tags.flag("Hemisphere Y", true), tags.flag("Hemisphere Z", true), color);
            }
            case "DrawCylinder" -> {
                if (!tags.has("Radius", FLOAT)) return;
                var orientation = switch (tags.word("Orientation Axis", AXIS, 2)) {
                    case 0 -> new Quaternionf().rotationZ((float)(Math.PI / 2)).rotateX((float)(Math.PI / 2));
                    case 1 -> new Quaternionf().rotationX((float)(Math.PI / 2));
                    default -> new Quaternionf();
                };
                owner.rotation(tags, "OrientationAxisCoordinateSpace").mul(orientation, orientation);
                int halfHeight = tags.word("IsHalfHeight", BOOL, 0);
                float height = tags.number("Height", 5) * ((halfHeight & 255) != 0 ? 2 : 1);
                NiagaraDebugGeometry.cylinder(lines, center, orientation, height, tags.number("Radius", 10),
                        tags.integer("Num Height Segments", 1), tags.integer("Num Radius Segments", 16), color,
                        tags.flag("Hemisphere X", false), tags.flag("Hemisphere Y", false),
                        tags.vector("NonUniform Scale", VEC3, ONE));
            }
            case "DrawCone" -> {
                if (!tags.has("Length", FLOAT) && !tags.has("Angle", FLOAT)) return;
                var orientation = NiagaraDebugGeometry.alignUp(tags.vector("Orientation Axis", VEC3, Z_AXIS), true);
                owner.rotation(tags, "OrientationAxisCoordinateSpace").mul(orientation, orientation);
                float length = tags.number("Length", 10);
                float radius = length * (float)Math.tan(Math.toRadians(tags.number("Angle", 25)));
                NiagaraDebugGeometry.cone(lines, center, orientation, length, radius, 0,
                        tags.integer("Num Height Segments", 1), tags.integer("Num Radius Segments", 16), color,
                        tags.vector("NonUniform Scale", VEC3, ONE), false);
            }
            case "DrawTorus" -> {
                if (!tags.has("MinorRadius", FLOAT) && !tags.has("MajorRadius", FLOAT)) return;
                Vector3f axis = owner.rotation(tags, "OrientationAxisCoordinateSpace")
                        .transform(tags.vector("Orientation Axis", VEC3, Z_AXIS));
                // The fixed source reads these two segment tags as int32 payloads of the float type.
                int major = Math.clamp(tags.word("MajorRadiusSegments", FLOAT, 16), 3, 32);
                int minor = Math.clamp(tags.word("MinorRadiusSegments", FLOAT, 8), 3, 32);
                NiagaraDebugGeometry.torus(lines, center, NiagaraDebugGeometry.normalized(axis),
                        tags.number("MajorRadius", 25), tags.number("MinorRadius", 5), major, minor, color);
            }
            case "DrawCoordinateSystem" -> {
                if (tags.has("Scale", FLOAT)) NiagaraDebugGeometry.coordinates(lines, center,
                        owner.orientation(tags, "Rotation", "RotationCoordinateSpace", SIMULATION), tags.number("Scale", 1));
            }
            case "DrawGrid2D", "DrawGrid3D" -> {
                boolean volume = function.equals("DrawGrid3D");
                String type = volume ? VEC3 : VEC2;
                if (tags.has("Extents", type)) NiagaraDebugGeometry.grid(lines, center,
                        owner.orientation(tags, "Rotation", "RotationCoordinateSpace", SIMULATION), tags.vector("Extents", type, ZERO),
                        tags.integer("NumCellsX", 10), tags.integer("NumCellsY", 10), tags.integer("NumCellsZ", 10), color, volume);
            }
            default -> throw new IllegalArgumentException("Unknown persistent Niagara Debug Draw function: " + function);
        }
    }

    private static void plane(String function, Tags tags, Owner owner, NiagaraDebugGeometry.Lines lines, Vector3f center, int color) {
        boolean circle = function.equals("DrawCircle");
        if (circle ? !tags.has("Radius", FLOAT) : !tags.has("Extents", VEC2)) return;
        Vector3f x = owner.vector(tags, "XAxis", "AxisVectorsCoordinateSpace", X_AXIS, SIMULATION, false);
        Vector3f y = owner.vector(tags, "YAxis", "AxisVectorsCoordinateSpace", Y_AXIS, SIMULATION, false);
        if (circle) NiagaraDebugGeometry.circle(lines, center, x, y, tags.number("Radius", 10), tags.integer("NumSegments", 1), color);
        else {
            Vector3f extent = tags.vector("Extents", VEC2, ONE).mul(tags.flag("HalfExtents", true) ? .5f : 1);
            NiagaraDebugGeometry.rectangle(lines, center, x, y, extent.x, extent.y,
                    tags.integer("NumXSegments", 1), tags.integer("NumYSegments", 1), color, tags.flag("UnboundedPlane", false));
        }
    }

    private record Tags(NiagaraPersistentDebugShape shape, NiagaraParameterStore overrides) {
        private NiagaraPersistentDebugShape.@Nullable Tag find(String name, String type) {
            for (var tag : shape.tags()) {
                if (!SourceNames.equal(tag.name(), name) || !SourceNames.equal(tag.binding().sourceType(), type)) continue;
                if (!tag.words().isEmpty() || !tag.binding().name().isEmpty() && overrides.numericBytes(tag.binding()) == tag.bytes())
                    return tag;
            }
            return null;
        }

        boolean has(String name, String type) { return find(name, type) != null; }
        int space(int coordinate) { return coordinate == SIMULATION ? shape.local() ? LOCAL : WORLD : coordinate; }
        int coordinate(String name, boolean provided) { return provided ? space(word(name, SPACE, SIMULATION)) : LOCAL; }
        int word(String name, String type, int fallback) {
            var tag = find(name, type);
            return tag == null ? fallback : word(tag, 0);
        }
        float number(String name, float fallback) { return Float.intBitsToFloat(word(name, FLOAT, Float.floatToRawIntBits(fallback))); }
        int integer(String name, int fallback) { return word(name, INT, fallback); }
        boolean flag(String name, boolean fallback) { return word(name, BOOL, fallback ? -1 : 0) != 0; }
        Vector3f vector(String name, String type, Vector3f fallback) {
            var tag = find(name, type);
            return tag == null ? new Vector3f(fallback) : new Vector3f(number(tag, 0), number(tag, 1), type.equals(VEC2) ? 0 : number(tag, 2));
        }
        Quaternionf quaternion(String name) {
            var tag = find(name, QUAT);
            return tag == null ? new Quaternionf(IDENTITY) : new Quaternionf(number(tag, 0), number(tag, 1), number(tag, 2), number(tag, 3));
        }
        int color(int fallback) {
            var tag = find("Color", COLOR);
            if (tag == null) return fallback;
            int rgba = 0;
            for (int index = 0; index < 4; index++) rgba = rgba << 8 | (int)(Math.clamp(number(tag, index), 0f, 1f) * 255f);
            return rgba;
        }
        private float number(NiagaraPersistentDebugShape.Tag tag, int index) { return Float.intBitsToFloat(word(tag, index)); }
        private int word(NiagaraPersistentDebugShape.Tag tag, int index) {
            if (!tag.words().isEmpty()) return tag.words().get(index);
            return overrides.numericWord(overrides.numericOffset(tag.binding()) + index * Integer.BYTES);
        }
    }

    private static final class Owner {
        private final Vector3d translation, scale, inverseScale;
        private final Quaterniond rotation, inverseRotation;

        Owner(Pose pose) {
            translation = new Vector3d(pose.position().x * 100, pose.position().z * 100, pose.position().y * 100);
            scale = new Vector3d(pose.scaleX(), pose.scaleZ(), pose.scaleY());
            inverseScale = new Vector3d(reciprocal(scale.x), reciprocal(scale.y), reciprocal(scale.z));
            rotation = new Quaterniond(-pose.x(), -pose.z(), -pose.y(), pose.w());
            inverseRotation = new Quaterniond(rotation).conjugate();
        }
        Vector3f vector(Tags tags, String name, String space, Vector3f fallback, int destination, boolean position) {
            return convert(tags.vector(name, VEC3, fallback), tags.coordinate(space, tags.has(name, VEC3)), tags.space(destination), position);
        }
        Vector3f convert(Vector3f input, int source, int destination, boolean position) {
            if (source == destination) return input;
            var result = new Vector3d(input);
            if (source == LOCAL && destination == WORLD) {
                rotation.transform(result.mul(scale));
                if (position) result.add(translation);
            } else if (source == WORLD && destination == LOCAL) {
                if (position) result.sub(translation);
                inverseRotation.transform(result).mul(inverseScale);
            }
            return input.set((float)result.x, (float)result.y, (float)result.z);
        }
        Quaternionf orientation(Tags tags, String name, String space, int destination) {
            Quaternionf value = tags.quaternion(name);
            int source = tags.coordinate(space, tags.has(name, QUAT));
            int target = tags.space(destination);
            if (source == LOCAL && target == WORLD) return worldRotation().mul(value);
            if (source == WORLD && target == LOCAL) return worldRotation().conjugate().mul(value);
            return value;
        }
        Quaternionf rotation(Tags tags, String space) {
            return tags.coordinate(space, true) == LOCAL ? worldRotation() : new Quaternionf();
        }
        Vector3f scale(Tags tags, String space) {
            return tags.coordinate(space, true) == LOCAL ? new Vector3f((float)scale.x, (float)scale.y, (float)scale.z) : new Vector3f(1);
        }
        private Quaternionf worldRotation() {
            return new Quaternionf((float)rotation.x, (float)rotation.y, (float)rotation.z, (float)rotation.w);
        }
        private static double reciprocal(double component) { return Math.abs(component) <= 1.e-8 ? 0 : 1 / component; }
    }
}
