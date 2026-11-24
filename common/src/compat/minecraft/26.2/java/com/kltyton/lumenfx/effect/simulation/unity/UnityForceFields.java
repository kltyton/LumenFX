package com.kltyton.lumenfx.effect.simulation.unity;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldConfig.Shape;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Samples exported force fields and wind zones in the owning simulation worker. */
public final class UnityForceFields {
    private record Field(UnityExternalForces.Field source, Matrix4f basis, Matrix4f inverse, float halfLength, int ordinal) { }
    private record Wind(UnityExternalForces.WindZone source, Vec3 center, Vec3 direction, int ordinal) { }

    private final UnityExternalForces definition;
    private final List<Field> authored;
    private final List<Wind> authoredWinds;
    private List<Field> fields;
    private List<Wind> winds;
    private Map<String, FieldState> fieldStates = Map.of();
    private final boolean localSimulation;
    private final Vector3f local = new Vector3f(), scratch = new Vector3f(), velocity = new Vector3f();
    private final Vector3d rootPosition = new Vector3d(), rootVelocity = new Vector3d();
    private final Quaterniond rootRotation = new Quaterniond(), inverseRotation = new Quaterniond();
    private final Quaterniond simulationRotation = new Quaterniond(), inverseSimulationRotation = new Quaterniond();
    private final double[] vector = new double[3];
    private Pose pose = Pose.at(Vec3.ZERO);
    private Pose simulationPose = pose;
    private Vec3 simulationOffset = Vec3.ZERO;
    private boolean separateLocalFrame;

    public UnityForceFields(UnityExternalForces definition, boolean localSimulation) {
        this.definition = definition;
        this.localSimulation = localSimulation;
        var prepared = new ArrayList<Field>(definition.fields().size());
        for (var source : definition.fields()) {
            prepared.add(prepare(source, prepared.size(), source.center(), source.xAxis(), source.yAxis(), source.zAxis()));
        }
        authored = List.copyOf(prepared);
        fields = authored.stream().filter(field -> field.source().enabled()).toList();
        var preparedWinds = new ArrayList<Wind>(definition.windZones().size());
        for (var source : definition.windZones()) {
            preparedWinds.add(new Wind(source, source.center(), source.direction(), preparedWinds.size()));
        }
        authoredWinds = List.copyOf(preparedWinds);
        winds = authoredWinds.stream().filter(wind -> wind.source().enabled()).toList();
    }

    private static Field prepare(UnityExternalForces.Field source, int ordinal, Vec3 center, Vec3 x, Vec3 y, Vec3 z) {
        var basis = new Matrix4f().m00((float)x.x).m01((float)x.y).m02((float)x.z)
                .m10((float)y.x).m11((float)y.y).m12((float)y.z)
                .m20((float)z.x).m21((float)z.y).m22((float)z.z)
                .m30((float)center.x).m31((float)center.y).m32((float)center.z);
        return new Field(source, basis, new Matrix4f(basis).invert(), source.length() * .5f, ordinal);
    }

    private static Wind prepareWind(UnityExternalForces.WindZone source, int ordinal,
                                    UnityExternalForces.@Nullable Basis basis) {
        if (basis == null) return new Wind(source, source.center(), source.direction(), ordinal);
        Vec3 forward = basis.zAxis();
        double length = forward.length();
        Vec3 direction = length > 1e-9 ? forward.scale(1.0 / length) : source.direction();
        return new Wind(source, basis.center(), direction, ordinal);
    }

    public void pose(Pose simulation, @Nullable Pose geometry) {
        pose(simulation, geometry, Map.of());
    }

    public void pose(Pose simulation, @Nullable Pose geometry, Map<String, FieldState> states) {
        if (fieldStates != states) {
            Set<String> matched = states.isEmpty() ? Set.of() : new HashSet<>();
            var resolved = new ArrayList<Field>(authored.size());
            for (var field : authored) {
                var source = field.source();
                String id = source.sourceId();
                var override = id == null ? null : states.get(id);
                if (override != null) matched.add(id);
                if (override == null ? !source.enabled() : !override.enabled()) continue;
                var activeSource = override != null && override.dynamicField() != null ? override.dynamicField() : source;
                var basis = override == null ? null : override.basis();
                resolved.add(basis == null && activeSource == source ? field
                        : prepare(activeSource, field.ordinal(),
                                basis != null ? basis.center() : activeSource.center(),
                                basis != null ? basis.xAxis() : activeSource.xAxis(),
                                basis != null ? basis.yAxis() : activeSource.yAxis(),
                                basis != null ? basis.zAxis() : activeSource.zAxis()));
            }
            var resolvedWinds = new ArrayList<Wind>(authoredWinds.size());
            for (var wind : authoredWinds) {
                var source = wind.source();
                String id = source.sourceId();
                var override = id == null ? null : states.get(id);
                if (override != null) matched.add(id);
                if (override == null ? !source.enabled() : !override.enabled()) continue;
                var activeSource = override != null && override.dynamicWindZone() != null ? override.dynamicWindZone() : source;
                var basis = override == null ? null : override.basis();
                resolvedWinds.add(basis == null && activeSource == source ? wind
                        : prepareWind(activeSource, wind.ordinal(), basis));
            }
            int nextFieldOrdinal = authored.size();
            int nextWindOrdinal = authoredWinds.size();
            var dynamicEntries = new ArrayList<Map.Entry<String, FieldState>>();
            for (var entry : states.entrySet()) {
                if (matched.contains(entry.getKey())) continue;
                if (!entry.getValue().enabled()) continue;
                dynamicEntries.add(entry);
            }
            if (dynamicEntries.size() > 1) dynamicEntries.sort(Map.Entry.comparingByKey());
            for (var entry : dynamicEntries) {
                var state = entry.getValue();
                if (state.dynamicField() != null) {
                    var dynamic = state.dynamicField();
                    var basis = state.basis();
                    resolved.add(prepare(dynamic, nextFieldOrdinal++,
                            basis != null ? basis.center() : dynamic.center(),
                            basis != null ? basis.xAxis() : dynamic.xAxis(),
                            basis != null ? basis.yAxis() : dynamic.yAxis(),
                            basis != null ? basis.zAxis() : dynamic.zAxis()));
                } else if (state.dynamicWindZone() != null) {
                    resolvedWinds.add(prepareWind(state.dynamicWindZone(), nextWindOrdinal++, state.basis()));
                }
            }
            fields = List.copyOf(resolved);
            winds = List.copyOf(resolvedWinds);
            fieldStates = states;
        }
        simulationPose = simulation;
        pose = geometry == null ? simulation : geometry;
        separateLocalFrame = localSimulation && !simulation.equals(pose);
        rootRotation.set(pose.x(), pose.y(), pose.z(), pose.w());
        inverseRotation.set(rootRotation).conjugate();
        if (separateLocalFrame) {
            simulationOffset = simulation.position().subtract(pose.position());
            simulationRotation.set(simulation.x(), simulation.y(), simulation.z(), simulation.w());
            inverseSimulationRotation.set(simulationRotation).conjugate();
        }
    }

    public boolean apply(double x, double y, double z, double vx, double vy, double vz,
                         double age, double random, float size, float seconds) {
        return apply(x, y, z, vx, vy, vz, age, random, size, seconds, age);
    }

    public boolean apply(double x, double y, double z, double vx, double vy, double vz,
                         double age, double random, float size, float seconds, double simulationTime) {
        if (fields.isEmpty() && winds.isEmpty()) return false;
        float multiplier = (float) definition.multiplier().sample(age, random);
        if (multiplier == 0 || pose.scaleX() == 0 || pose.scaleY() == 0 || pose.scaleZ() == 0
                || separateLocalFrame && (simulationPose.scaleX() == 0 || simulationPose.scaleY() == 0
                        || simulationPose.scaleZ() == 0)) return false;
        rootPosition.set(x, y, z);
        rootVelocity.set(vx, vy, vz);
        if (separateLocalFrame) {
            simulationRotation.transform(rootPosition.mul(simulationPose.scaleX(), simulationPose.scaleY(),
                    simulationPose.scaleZ())).add(simulationOffset.x, simulationOffset.y, simulationOffset.z);
            simulationRotation.transform(rootVelocity.mul(simulationPose.scaleX(), simulationPose.scaleY(),
                    simulationPose.scaleZ()));
        }
        if (!localSimulation || separateLocalFrame) {
            var position = pose.position();
            if (!localSimulation) rootPosition.sub(position.x, position.y, position.z);
            inverseRotation.transform(rootPosition);
            inverseRotation.transform(rootVelocity);
            rootPosition.div(pose.scaleX(), pose.scaleY(), pose.scaleZ());
            rootVelocity.div(pose.scaleX(), pose.scaleY(), pose.scaleZ());
        }
        boolean influenced = false;
        for (var field : fields) {
            local.set((float)rootPosition.x, (float)rootPosition.y, (float)rootPosition.z).mulPosition(field.inverse());
            float normalizedDistance = normalizedDistance(field, local);
            if (normalizedDistance < 0) continue;
            if (!influenced) velocity.set((float)rootVelocity.x, (float)rootVelocity.y, (float)rootVelocity.z);
            influenced = true;
            applyField(field, normalizedDistance, random, size, seconds * multiplier, field.ordinal());
        }
        for (var wind : winds) {
            if (applyWind(wind, simulationTime, random, seconds * multiplier, influenced)) influenced = true;
        }
        if (!influenced) return false;
        rootVelocity.set(velocity.x, velocity.y, velocity.z);
        if (!localSimulation || separateLocalFrame)
            rootRotation.transform(rootVelocity.mul(pose.scaleX(), pose.scaleY(), pose.scaleZ()));
        if (separateLocalFrame)
            inverseSimulationRotation.transform(rootVelocity).div(simulationPose.scaleX(), simulationPose.scaleY(),
                    simulationPose.scaleZ());
        return true;
    }

    public Vector3d velocity() { return rootVelocity; }

    private static float normalizedDistance(Field field, Vector3f local) {
        var source = field.source();
        float start = source.start(), end = source.end();
        if (end <= 0 || start > end) return -1f;
        if (source.shape() == Shape.Hemisphere && local.y < 0 || source.shape() == Shape.Cylinder
                && Math.abs(local.y) > field.halfLength()) return -1f;
        float radius = switch (source.shape()) {
            case Sphere, Hemisphere -> local.length();
            case Box -> Math.max(Math.abs(local.x), Math.max(Math.abs(local.y), Math.abs(local.z)));
            case Cylinder -> (float)Math.sqrt(local.x * local.x + local.z * local.z);
        };
        if (radius < start || radius > end) return -1f;
        return start == end ? 0f : Math.clamp((radius - start) / (end - start), 0f, 1f);
    }

    private void applyField(Field field, float normalizedDistance, double random, float size, float step, int index) {
        var source = field.source();
        var basis = field.basis();
        var axes = source.direction();
        scratch.set((float) axes.get(0).sample(normalizedDistance, random),
                (float) axes.get(1).sample(normalizedDistance, random),
                (float) axes.get(2).sample(normalizedDistance, random));
        ForceFieldResponse.direction(basis, scratch, velocity, step);
        Vector3f gravityOffset = source.shape() == Shape.Cylinder ? new Vector3f(local.x, 0f, local.z) : local;
        ForceFieldResponse.gravity(basis, gravityOffset, velocity, scratch,
                (float)source.gravity().sample(normalizedDistance, random),
                source.gravityFocus() * source.end(), 30f, 1, step);
        float rotation = (float)source.rotationSpeed().sample(normalizedDistance, random);
        float rotationAttraction = (float)source.rotationAttraction().sample(normalizedDistance, random);
        if (rotation != 0 && rotationAttraction > 0) {
            double x = random + index * .6180339887498948;
            double z = random + index * .4142135623730950 + .5;
            float anchorX = source.randomX() * (float)(2 * (x - Math.floor(x)) - 1);
            float anchorZ = source.randomZ() * (float)(2 * (z - Math.floor(z)) - 1);
            float radialX = local.x - anchorX, radialZ = local.z - anchorZ;
            if (radialX * radialX + radialZ * radialZ > 1e-8f) {
                scratch.set(-radialZ, 0, radialX);
                basis.transformDirection(scratch).normalize(rotation);
                float alpha = Math.clamp(rotationAttraction * 30f * step, 0f, 1f);
                velocity.lerp(scratch, alpha);
            }
        }
        var volume = source.volume();
        if (volume != null) {
            float attraction = (float)source.vectorAttraction().sample(normalizedDistance, random) * 30f * step;
            if (attraction != 0) {
                volume.sampleNearest(local.x, local.y, local.z, vector, 0);
                float speed = (float)source.vectorSpeed().sample(normalizedDistance, random);
                scratch.set((float)vector[0] * speed, (float)vector[1] * speed, (float)vector[2] * speed);
                basis.transformDirection(scratch);
                velocity.add((scratch.x - velocity.x) * attraction, (scratch.y - velocity.y) * attraction,
                        (scratch.z - velocity.z) * attraction);
            }
        }
        float drag = (float)source.drag().sample(normalizedDistance, random);
        if (drag > 0) {
            float area = source.dragBySize() ? (float)(Math.PI * (size * 0.5f) * (size * 0.5f)) : 1f;
            ForceFieldResponse.drag(velocity, size, drag, area, step, false, source.dragBySpeed());
        }
    }

    private boolean applyWind(Wind wind, double time, double random, float step, boolean alreadyInfluenced) {
        var source = wind.source();
        double dirX, dirY, dirZ, attenuation;
        if (source.mode() == UnityExternalForces.WindZone.Mode.SPHERICAL) {
            float radius = source.radius();
            if (radius <= 0) return false;
            double dx = rootPosition.x - wind.center().x;
            double dy = rootPosition.y - wind.center().y;
            double dz = rootPosition.z - wind.center().z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > radius || distance <= 1e-8) return false;
            attenuation = 1.0 - distance / radius;
            dirX = dx / distance;
            dirY = dy / distance;
            dirZ = dz / distance;
        } else {
            attenuation = 1.0;
            dirX = wind.direction().x;
            dirY = wind.direction().y;
            dirZ = wind.direction().z;
        }
        double pulse = source.pulseMagnitude() == 0 || source.pulseFrequency() == 0 ? 0.0
                : source.pulseMagnitude() * Math.sin(2.0 * Math.PI * source.pulseFrequency() * time);
        double magnitude = (source.main() + pulse) * attenuation;
        double turbX = 0, turbY = 0, turbZ = 0;
        if (source.turbulence() != 0) {
            double phase = time * 3.0 + wind.ordinal() * 1.618033988749895 + random * 6.283185307179586;
            double scale = source.turbulence() * attenuation;
            turbX = Math.sin(phase + rootPosition.x * 0.7) * scale;
            turbY = Math.sin(phase * 1.31 + rootPosition.y * 0.7 + 2.0943951023931953) * scale;
            turbZ = Math.sin(phase * 0.83 + rootPosition.z * 0.7 + 4.1887902047863905) * scale;
        }
        if (magnitude == 0 && turbX == 0 && turbY == 0 && turbZ == 0) return false;
        if (!alreadyInfluenced) velocity.set((float)rootVelocity.x, (float)rootVelocity.y, (float)rootVelocity.z);
        velocity.add((float)((dirX * magnitude + turbX) * step),
                (float)((dirY * magnitude + turbY) * step),
                (float)((dirZ * magnitude + turbZ) * step));
        return true;
    }
}
