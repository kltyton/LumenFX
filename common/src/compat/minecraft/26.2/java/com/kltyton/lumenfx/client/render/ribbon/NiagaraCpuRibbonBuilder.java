package com.kltyton.lumenfx.client.render.ribbon;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.lowdragmc.photon.client.gameobject.particle.renderer.TrailSpline;

import com.kltyton.lumenfx.client.render.EffectMesh;
import com.kltyton.lumenfx.client.render.niagara.NiagaraComponentGeometry;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentDefaults;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParticles;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraRibbonScalability;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.kltyton.lumenfx.effect.render.RibbonShapeGeometry;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Native CPU ribbon sampling and instance packing; curves and cross-sections retain source float centimetres. */
public final class NiagaraCpuRibbonBuilder {
    private static final int ROW_STRIDE = 39, PARTICLE = 4, DYNAMIC = 8, U0 = 24, U1 = 25, V0 = 26, V1 = 28;
    private static final int UV_DISTANCE = 30, WIDTH = 31, TWIST = 32, FACING = 33, VELOCITY = 36;
    private static final class Point {
        final float[] simulation = new float[3], center = new float[3], direction = new float[3], right = new float[3];
        final float[] facing = new float[3], up = new float[3], velocity = new float[3], approximate = new float[3];
        final float[] world = new float[6], approximateWorld = new float[6];
        final float[] color = new float[4], particle = new float[4], dynamic = new float[16], vBounds = new float[4];
        float width, u0, u1;
    }

    private final NiagaraCpuRibbonData generated = new NiagaraCpuRibbonData();
    private final NiagaraComponentGeometry component = new NiagaraComponentGeometry();
    private final Point first = new Point(), second = new Point(), oldFirst = new Point(), oldSecond = new Point();
    private final float[] instance = new float[59], extra = new float[84], vertex = new float[3];
    private float[] attributes = new float[0];
    private NiagaraParticles particles;
    private EmitterInstance.Snapshot snapshot;
    private RibbonSettings settings;
    private boolean local;
    private boolean sourceFrameDeltaKnown;
    private float sourceFrameDelta;
    private int dynamicMask, motionMode;

    public void append(EffectMesh output, EmitterInstance.Snapshot snapshot, Vec3 camera, float lodDistanceFactor,
                       RibbonShapeGeometry shape, Vec3 viewDirection) {
        append(output, snapshot, camera, lodDistanceFactor, shape, viewDirection, false, 0, false);
    }

    public void append(EffectMesh output, EmitterInstance.Snapshot snapshot, Vec3 camera, float lodDistanceFactor,
                       RibbonShapeGeometry shape, Vec3 viewDirection, float sourceFrameDelta) {
        append(output, snapshot, camera, lodDistanceFactor, shape, viewDirection, true, sourceFrameDelta, false);
    }

    public void appendPointShadow(EffectMesh output, EmitterInstance.Snapshot snapshot, Vec3 camera, float lodDistanceFactor,
                                  RibbonShapeGeometry shape, Vec3 viewDirection, float sourceFrameDelta) {
        append(output, snapshot, camera, lodDistanceFactor, shape, viewDirection, true, sourceFrameDelta, true);
    }

    private void append(EffectMesh output, EmitterInstance.Snapshot snapshot, Vec3 camera, float lodDistanceFactor,
                        RibbonShapeGeometry shape, Vec3 viewDirection, boolean sourceFrameDeltaKnown, float sourceFrameDelta,
                        boolean pointShadow) {
        if (output.kind() != EffectMesh.Kind.RIBBON) throw new IllegalArgumentException("Ribbon geometry requires a ribbon instance stream");
        particles = Objects.requireNonNull(snapshot.nativeParticles(), "Native ribbon particle data");
        settings = snapshot.nativeParticles().ribbonSettings(
                Objects.requireNonNull(snapshot.definition().ribbonSettings(), "Native ribbon settings"));
        this.snapshot = snapshot;
        this.sourceFrameDeltaKnown = sourceFrameDeltaKnown;
        this.sourceFrameDelta = sourceFrameDelta;
        local = snapshot.definition().localSpace();
        dynamicMask = particles.dynamicParameterMask();
        motionMode = particles.motion().precise() ? 1 : 2;
        var scalability = NiagaraRibbonScalability.from(NiagaraPlatforms.snapshot());
        if (!generated.generate(particles, settings, scalability)) return;
        cacheAttributes(output.material().dynamicSlots() & 14);
        float viewDistance = NiagaraComponentDefaults.RENDERER_LOD_DISTANCE_OVERRIDE;
        int subdivisions = pointShadow ? snapshot.tessellation().sourceSubdivisions(settings, scalability, viewDistance, lodDistanceFactor)
                : snapshot.tessellation().updateSource(settings, scalability, generated.metrics(), particles.hasRibbonTwistAndWidth(),
                        viewDistance, lodDistanceFactor);
        if (generated.pointCount() == 0) return;
        component.prepare(particles, camera);
        float reciprocalSubdivisions = 1f / subdivisions;
        for (var strip : generated.strips()) {
            boolean reverse = output.material().blend().translucent() && reverse(strip, camera, viewDirection);
            int begin = reverse ? strip.last() - 1 : strip.first(), end = reverse ? strip.first() - 1 : strip.last();
            int increment = reverse ? -1 : 1;
            for (int point = begin; point != end; point += increment) {
            for (int step = 0; step < subdivisions; step++) {
                float alpha = (float)step * reciprocalSubdivisions;
                int nextPoint = point, nextEnd = point + 1;
                float nextAlpha = (float)(step + 1) * reciprocalSubdivisions;
                if (step + 1 == subdivisions) {
                    nextPoint = point + 1; nextEnd = Math.min(nextPoint + 1, strip.last()); nextAlpha = 0;
                }
                sample(first, point, point + 1, alpha, false, strip);
                sample(second, nextPoint, nextEnd, nextAlpha, false, strip);
                if (motionMode == 1) {
                    sample(oldFirst, point, point + 1, alpha, true, strip);
                    sample(oldSecond, nextPoint, nextEnd, nextAlpha, true, strip);
                }
                int flipGeometryIndex = Math.min(Math.max(shape.edges().size() / 2, 2), shape.edges().size());
                for (int edge = 0; edge < shape.edges().size(); edge += 2)
                    write(output, shape.vertices().get(shape.edges().get(edge)), shape.vertices().get(shape.edges().get(edge + 1)),
                            edge < flipGeometryIndex);
            }
        }
        }
    }

    private boolean reverse(NiagaraCpuRibbonData.Strip strip, Vec3 camera, Vec3 viewDirection) {
        int first = strip.sourceFirstRow(), last = strip.sourceLastRow();
        double start = viewDirection.x * (particles.simulationPosition(first, 0) - camera.x * 100)
                + viewDirection.z * (particles.simulationPosition(first, 1) - camera.z * 100)
                + viewDirection.y * (particles.simulationPosition(first, 2) - camera.y * 100);
        double end = viewDirection.x * (particles.simulationPosition(last, 0) - camera.x * 100)
                + viewDirection.z * (particles.simulationPosition(last, 1) - camera.z * 100)
                + viewDirection.y * (particles.simulationPosition(last, 2) - camera.y * 100);
        return start >= end && settings.drawDirection() == RibbonSettings.DrawDirection.BACK_TO_FRONT
                || start < end && settings.drawDirection() == RibbonSettings.DrawDirection.FRONT_TO_BACK;
    }

    public void clear() { generated.clear(); particles = null; snapshot = null; settings = null; }

    private void cacheAttributes(int extraSlots) {
        int count = particles.count(), required = Math.multiplyExact(count, ROW_STRIDE);
        if (attributes.length < required) attributes = new float[Math.max(required, Math.max(256 * ROW_STRIDE, Math.multiplyExact(attributes.length, 2)))];
        for (int row = 0; row < count; row++) {
            int base = row * ROW_STRIDE;
            for (int channel = 0; channel < 4; channel++) attributes[base + channel] = particles.color(row, channel);
            attributes[base + PARTICLE] = particles.ribbonNormalizedAge(row); attributes[base + PARTICLE + 1] = particles.random(row);
            attributes[base + PARTICLE + 2] = particles.lifetime(row); attributes[base + PARTICLE + 3] = particles.age(row);
            for (int channel = 0; channel < 4; channel++) attributes[base + DYNAMIC + channel] = particles.dynamicParameter(row, channel,
                    (float)snapshot.definition().customData().get(channel).sample(particles.normalizedAge(row), particles.random(row)));
            for (int slot = 1; slot < 4; slot++) for (int channel = 0; channel < 4; channel++)
                attributes[base + DYNAMIC + slot * 4 + channel] = (extraSlots & 1 << slot) != 0
                        ? particles.dynamicParameter(row, slot, channel) : 0;
            attributes[base + U0] = particles.ribbonU(row, 0); attributes[base + U1] = particles.ribbonU(row, 1);
            for (int axis = 0; axis < 2; axis++) {
                attributes[base + V0 + axis] = particles.ribbonV(row, 0, axis);
                attributes[base + V1 + axis] = particles.ribbonV(row, 1, axis);
            }
            attributes[base + UV_DISTANCE] = particles.ribbonUvDistanceSource(row);
            attributes[base + WIDTH] = particles.ribbonWidthSource(row); attributes[base + TWIST] = particles.ribbonTwist(row);
            for (int axis = 0; axis < 3; axis++) {
                attributes[base + FACING + axis] = particles.ribbonFacing(row, axis);
                attributes[base + VELOCITY + axis] = particles.ribbonVelocity(row, axis);
            }
        }
    }

    private void sample(Point output, int firstPoint, int secondPoint, float alpha, boolean previous, NiagaraCpuRibbonData.Strip strip) {
        int rowA = generated.row(firstPoint), rowB = generated.row(secondPoint);
        float span = generated.distance(secondPoint) - generated.distance(firstPoint);
        var weights = TrailSpline.hermiteWeights(alpha, span);
        for (int axis = 0; axis < 3; axis++) {
            float firstPosition = previous ? particles.previousSimulationPosition(rowA, axis) : particles.simulationPosition(rowA, axis);
            float secondPosition = previous ? particles.previousSimulationPosition(rowB, axis) : particles.simulationPosition(rowB, axis);
            output.simulation[axis] = weights.sample(firstPosition, secondPosition,
                    generated.tangent(firstPoint, axis), generated.tangent(secondPoint, axis));
            output.direction[axis] = lerp(generated.tangent(firstPoint, axis), generated.tangent(secondPoint, axis), alpha);
        }
        component.sourceWorldPosition(output.world, output.simulation[0], output.simulation[1], output.simulation[2], local, previous);
        component.sourceTranslatedPosition(output.center, output.world);
        component.sourceRibbonDirection(output.direction, 0, output.direction[0], output.direction[1], output.direction[2], local, previous);
        normalize(output.direction);
        boolean custom = settings.shape().facing() != RibbonSettings.Facing.SCREEN
                && (previous ? particles.hasPreviousRibbonFacing() : particles.hasRibbonFacing());
        if (custom) {
            for (int axis = 0; axis < 3; axis++) output.facing[axis] = previous
                    ? lerp(particles.previousRibbonFacing(rowA, axis), particles.previousRibbonFacing(rowB, axis), alpha)
                    : attribute(rowA, rowB, FACING + axis, alpha);
            component.sourceRibbonDirection(output.facing, 0, output.facing[0], output.facing[1], output.facing[2], local, previous);
        } else for (int axis = 0; axis < 3; axis++) output.facing[axis] = -output.center[axis];
        normalize(output.facing);
        cross(output.right, output.facing, output.direction);
        normalize(output.right);
        if (settings.shape().facing() == RibbonSettings.Facing.CUSTOM_SIDE_VECTOR) {
            for (int axis = 0; axis < 3; axis++) {
                float old = output.right[axis]; output.right[axis] = -output.facing[axis]; output.facing[axis] = old;
            }
        }
        if (particles.hasRibbonTwist(previous)) {
            float twist = previous ? lerp(particles.previousRibbonTwist(rowA), particles.previousRibbonTwist(rowB), alpha)
                    : attribute(rowA, rowB, TWIST, alpha);
            float sine = (float)Math.sin(twist), cosine = (float)Math.cos(twist);
            for (int axis = 0; axis < 3; axis++) {
                float right = output.right[axis], facing = output.facing[axis];
                output.right[axis] = cosine * right + sine * facing;
                output.facing[axis] = -sine * right + cosine * facing;
            }
        }
        cross(output.up, output.direction, output.right);
        output.width = previous ? lerp(particles.previousRibbonWidthSource(rowA), particles.previousRibbonWidthSource(rowB), alpha)
                : attribute(rowA, rowB, WIDTH, alpha);
        if (previous) return;
        for (int axis = 0; axis < 3; axis++) output.velocity[axis] = attribute(rowA, rowB, VELOCITY + axis, alpha);
        component.sourceRibbonDirection(output.velocity, 0, output.velocity[0], output.velocity[1], output.velocity[2], local, false);
        float speedSquared = dot(output.velocity, output.velocity);
        if (speedSquared > 0) {
            float inverse = 1f / (float)Math.sqrt(speedSquared), magnitude = 1f / inverse;
            float factor = sourceFrameDeltaKnown ? magnitude * sourceFrameDelta : magnitude;
            for (int axis = 0; axis < 3; axis++) {
                float direction = output.velocity[axis] * inverse;
                output.approximate[axis] = (sourceFrameDeltaKnown ? -direction : direction) * factor;
            }
        } else Arrays.fill(output.approximate, 0);
        component.sourceRibbonDirection(output.approximate, 0, output.approximate[0], output.approximate[1], output.approximate[2], local, false);
        if (sourceFrameDeltaKnown) {
            System.arraycopy(output.world, 0, output.approximateWorld, 0, output.world.length);
            for (int axis = 0; axis < 3; axis++) output.approximateWorld[3 + axis] += output.approximate[axis];
        }
        var tint = snapshot.input();
        for (int channel = 0; channel < 4; channel++) {
            float multiplier = switch (channel) { case 0 -> tint.red(); case 1 -> tint.green(); case 2 -> tint.blue(); default -> tint.alpha(); };
            output.color[channel] = attribute(rowA, rowB, channel, alpha) * multiplier;
            output.particle[channel] = attribute(rowA, rowB, PARTICLE + channel, alpha);
        }
        output.particle[3] = (float)snapshot.time() - output.particle[3];
        for (int channel = 0; channel < 16; channel++) output.dynamic[channel] = attribute(rowA, rowB, DYNAMIC + channel, alpha);
        output.u0 = u(settings.uv0(), strip.uv0(), rowA, rowB, firstPoint - strip.first(), alpha, firstPoint, secondPoint, 0);
        output.u1 = u(settings.uv1(), strip.uv1(), rowA, rowB, firstPoint - strip.first(), alpha, firstPoint, secondPoint, 1);
        for (int channel = 0; channel < 4; channel++) output.vBounds[channel] = attribute(rowA, rowB, V0 + channel, alpha);
    }

    private float u(RibbonSettings.Uv settings, NiagaraCpuRibbonData.Uv uv, int rowA, int rowB, int raw, float alpha,
                     int firstPoint, int secondPoint, int channel) {
        if (uv.override()) return attribute(rowA, rowB, channel == 0 ? U0 : U1, alpha);
        float value = switch (settings.distribution()) {
            case UNIFORM -> (float)raw + alpha;
            case LENGTH, DISTANCE -> lerp(generated.distance(firstPoint), generated.distance(secondPoint), alpha);
            case DISTANCE_FROM_START -> attribute(rowA, rowB, UV_DISTANCE, alpha);
        };
        return (value * uv.distribution()) * uv.scale() + uv.offset();
    }

    private void write(EffectMesh output, RibbonShapeGeometry.Vertex a, RibbonShapeGeometry.Vertex b, boolean alternateDiagonal) {
        corner(first, a, false, instance, 0); corner(second, a, false, instance, 3);
        corner(first, b, false, instance, 6); corner(second, b, false, instance, 9);
        instance[12] = first.u0; instance[13] = second.u0; instance[14] = first.u1; instance[15] = second.u1;
        instance[16] = (float)settings.uv0().scaleV(); instance[17] = (float)settings.uv0().offsetV();
        instance[18] = (float)settings.uv1().scaleV(); instance[19] = (float)settings.uv1().offsetV();
        attributes(first, instance, 20); attributes(second, instance, 32);
        instance[44] = dynamicMask | EffectMesh.RIBBON_EXPLICIT_CORNERS
                | (alternateDiagonal ? EffectMesh.RIBBON_ALTERNATE_DIAGONAL : 0);
        vertical(first, a.textureV(), b.textureV(), instance, 45);
        vertical(second, a.textureV(), b.textureV(), instance, 49);
        minecraft(first.center, .01f, instance, 53); minecraft(second.center, .01f, instance, 56);
        System.arraycopy(first.dynamic, 4, extra, 0, 12); System.arraycopy(second.dynamic, 4, extra, 12, 12);
        minecraft(first.velocity, 1, extra, 24); extra[27] = first.width;
        minecraft(second.velocity, 1, extra, 28); extra[31] = second.width;
        boolean preparedPrevious = motionMode == 1 || sourceFrameDeltaKnown;
        if (preparedPrevious) {
            Point start = motionMode == 1 ? oldFirst : first, end = motionMode == 1 ? oldSecond : second;
            corner(start, a, true, extra, 32); corner(start, b, true, extra, 36);
            corner(end, a, true, extra, 40); corner(end, b, true, extra, 44);
        }
        extra[35] = extra[43] = preparedPrevious ? 1 : motionMode; extra[39] = extra[47] = 0;
        minecraft(first.approximate, 1, extra, 48); minecraft(second.approximate, 1, extra, 52);
        extra[51] = extra[55] = 0;
        if (output.needsMaterialBasis()) {
            int base = output.materialBasisOffset();
            basis(first, extra, base); basis(second, extra, base + 12);
            extra[base + 3] = settings.shape().usesGeometryNormals() ? 1 : 0;
            extra[base + 7] = settings.shape().flipNormalToView() ? 1 : 0;
            extra[base + 24] = a.normalX(); extra[base + 25] = a.normalY(); extra[base + 26] = b.normalX(); extra[base + 27] = b.normalY();
        }
        output.appendInstance(instance, 0, extra, 0);
    }

    private void corner(Point point, RibbonShapeGeometry.Vertex shape, boolean previous, float[] target, int offset) {
        float x = (point.right[0] * shape.x() + point.up[0] * shape.y()) * point.width;
        float y = (point.right[1] * shape.x() + point.up[1] * shape.y()) * point.width;
        float z = (point.right[2] * shape.x() + point.up[2] * shape.y()) * point.width;
        float[] world = previous && motionMode == 2 && sourceFrameDeltaKnown ? point.approximateWorld : point.world;
        component.sourceTranslatedPositionOffset(vertex, world, x, y, z);
        minecraft(vertex, .01f, target, offset);
    }

    private void vertical(Point point, float first, float second, float[] target, int offset) {
        target[offset] = vertical(point, first, 0); target[offset + 1] = vertical(point, second, 0);
        target[offset + 2] = vertical(point, first, 1); target[offset + 3] = vertical(point, second, 1);
    }

    private float vertical(Point point, float value, int channel) {
        RibbonSettings.Uv uv = channel == 0 ? settings.uv0() : settings.uv1();
        if (!uv.vOverride() || !particles.hasRibbonV(channel)) return value;
        int offset = channel * 2;
        return (1f - value * point.vBounds[offset]) + value * point.vBounds[offset + 1];
    }

    private static void attributes(Point point, float[] target, int offset) {
        System.arraycopy(point.color, 0, target, offset, 4);
        System.arraycopy(point.particle, 0, target, offset + 4, 4);
        System.arraycopy(point.dynamic, 0, target, offset + 8, 4);
    }

    private static void basis(Point point, float[] target, int offset) {
        minecraft(point.direction, 1, target, offset); target[offset + 3] = 0;
        minecraft(point.right, 1, target, offset + 4); target[offset + 7] = 0;
        minecraft(point.facing, 1, target, offset + 8); target[offset + 11] = 0;
    }

    private float attribute(int first, int second, int field, float alpha) {
        return lerp(attributes[first * ROW_STRIDE + field], attributes[second * ROW_STRIDE + field], alpha);
    }
    private static float lerp(float first, float second, float alpha) { return first + alpha * (second - first); }
    private static float dot(float[] first, float[] second) { return (first[0] * second[0] + first[1] * second[1]) + first[2] * second[2]; }
    private static void normalize(float[] value) {
        float square = dot(value, value);
        if (square > 0) {
            float inverse = 1f / (float)Math.sqrt(square);
            for (int axis = 0; axis < 3; axis++) value[axis] *= inverse;
        } else Arrays.fill(value, 0);
    }
    private static void cross(float[] target, float[] first, float[] second) {
        target[0] = first[1] * second[2] - first[2] * second[1];
        target[1] = first[2] * second[0] - first[0] * second[2];
        target[2] = first[0] * second[1] - first[1] * second[0];
    }
    private static void minecraft(float[] source, float scale, float[] target, int offset) {
        target[offset] = source[0] * scale; target[offset + 1] = source[2] * scale; target[offset + 2] = source[1] * scale;
    }
}
