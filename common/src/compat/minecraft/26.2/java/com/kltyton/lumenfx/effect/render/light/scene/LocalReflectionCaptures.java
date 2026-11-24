package com.kltyton.lumenfx.effect.render.light.scene;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Source reflection proxies ordered by influence radius and source identity. Coordinates are in Unreal centimeters. */
public record LocalReflectionCaptures(Identifier textureArray, int mipCount, Vec3 luminanceWeights,
                                      Vec3 specularColorScale, List<Capture> captures) {
    public LocalReflectionCaptures {
        Objects.requireNonNull(textureArray);
        requireFinite(luminanceWeights);
        requireFinite(specularColorScale);
        captures = List.copyOf(captures);
        if (mipCount <= 0 || captures.isEmpty()) throw new IllegalArgumentException("Invalid reflection capture collection");
        for (int index = 1; index < captures.size(); index++)
            if (captures.get(index - 1).radius() > captures.get(index).radius())
                throw new IllegalArgumentException("Reflection captures must be ordered by native influence radius");
    }

    public record Capture(Vec3 position, float radius, Vec3 offset, float brightness, float averageBrightness, Shape shape,
                          int registrationIndex) {
        public Capture(Vec3 position, float radius, Vec3 offset, float brightness, float averageBrightness, Shape shape) {
            this(position, radius, offset, brightness, averageBrightness, shape, -1);
        }

        public Capture {
            requireFinite(position);
            requireFinite(offset);
            Objects.requireNonNull(shape);
            if (!Float.isFinite(radius) || radius <= 0 || !Float.isFinite(brightness)
                    || !Float.isFinite(averageBrightness) || averageBrightness < 0 || registrationIndex < -1)
                throw new IllegalArgumentException("Invalid source reflection capture");
        }
    }

    public Identifier preintegratedTexture() {
        return SkyLightIrradiance.Reflection.preintegratedTexture(textureArray);
    }

    /** Returns the cubemap's sorted proxy index. An uncovered primitive uses the black capture. */
    public int closestTo(Vec3 sourceBoundsOrigin) {
        float closestDistance = Float.MAX_VALUE;
        int closest = -1;
        int closestRegistration = -1;
        for (int i = 0; i < captures.size(); i++) {
            Capture capture = captures.get(i);
            float distance = (float)capture.position().distanceToSqr(sourceBoundsOrigin);
            double radius = capture.radius();
            int registration = capture.registrationIndex();
            boolean registeredEarlier = distance == closestDistance && registration >= 0
                    && closestRegistration >= 0 && registration < closestRegistration;
            if (distance <= radius * radius && (closest < 0 || distance < closestDistance || registeredEarlier)) {
                closestDistance = distance;
                closest = i;
                closestRegistration = registration;
            }
        }
        return closest;
    }

    public sealed interface Shape permits Sphere, Box, Plane { }
    public record Sphere() implements Shape { }
    public record Box(Vec3 relativeWorldToBoxX, Vec3 relativeWorldToBoxY, Vec3 relativeWorldToBoxZ,
                      Vec3 scales, float transition) implements Shape {
        public Box {
            requireFinite(relativeWorldToBoxX);
            requireFinite(relativeWorldToBoxY);
            requireFinite(relativeWorldToBoxZ);
            requireFinite(scales);
            if (!Float.isFinite(transition) || transition < 0) throw new IllegalArgumentException("Invalid box capture transition");
        }
    }
    public record Plane(Vec3 normal, float distance, Vec3 reflectionAxis, float axisScale) implements Shape {
        public Plane {
            requireFinite(normal);
            requireFinite(reflectionAxis);
            if (!Float.isFinite(distance) || !Float.isFinite(axisScale))
                throw new IllegalArgumentException("Invalid plane capture transform");
        }
    }

    private static void requireFinite(Vec3 value) {
        Objects.requireNonNull(value);
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z))
            throw new IllegalArgumentException("Invalid reflection capture vector");
    }
}
