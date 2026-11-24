package com.kltyton.lumenfx.effect;

import com.kltyton.lumenfx.effect.curve.ColorFunction;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.noise.FieldSampling;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.kltyton.lumenfx.effect.render.SpriteCutout;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.kltyton.lumenfx.effect.render.light.LightRendererSettings;
import com.kltyton.lumenfx.effect.render.decal.DecalRendererSettings;
import com.kltyton.lumenfx.effect.render.splat.SplatRendererSettings;
import com.kltyton.lumenfx.effect.render.volume.VolumeRendererSettings;
import com.kltyton.lumenfx.effect.unity.UnityShapeSettings;
import org.jspecify.annotations.Nullable;
import java.util.List;
import net.minecraft.world.phys.Vec3;

public record EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                                double delay, double duration, boolean looping, int maximumParticles,
                                ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                                ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                                ScalarFunction rotation, ScalarFunction angularVelocity,
                                ScalarFunction startSpeed, Vec3 startDirection, ColorFunction startColor,
                                Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                                List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife,
                                Shape shape, ScalarFunction sizeOverLife, ColorFunction colorOverLife,
                                List<ScalarFunction> customData, Noise noise, Integration integration, Randomization randomization,
                                UvMode uvMode, double uvDistance, int atlasColumns, int atlasRows,
                                ScalarFunction atlasFrame, int subdivisions, int widthSegments, boolean attachHead,
                                RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                                int atlasRow, double macroUVRadius, @Nullable SpriteCutout spriteCutout,
                                @Nullable MeshRendererSettings meshSettings, @Nullable LightRendererSettings lightSettings,
                                @Nullable DecalRendererSettings decalSettings, @Nullable VolumeRendererSettings volumeSettings,
                                @Nullable SplatRendererSettings splatSettings,
                                boolean castsShadows, @Nullable ScalarFunction rateOverDistance) {
    public EmitterDefinition inLocalSpace() {
        return drawDefinition(true, sortOrder);
    }

    public EmitterDefinition withSortOrder(int order) { return drawDefinition(localSpace, order); }

    private EmitterDefinition drawDefinition(boolean localSpace, int sortOrder) {
        if (this.localSpace == localSpace && this.sortOrder == sortOrder) return this;
        return new EmitterDefinition(name, renderer, material, localSpace, delay, duration, looping, maximumParticles,
                rate, distanceSpacing, movementThreshold, bursts, lifetime, mass, width, height, rotation, angularVelocity,
                startSpeed, startDirection, startColor, velocityMinimum, velocityMaximum, acceleration, drag,
                velocityOverLife, forceOverLife, shape, sizeOverLife, colorOverLife, customData, noise, integration, randomization,
                uvMode, uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions, widthSegments, attachHead, ribbonSettings,
                scaleSizeWithOwner, sortOrder, sortMode, atlasRow, macroUVRadius, spriteCutout, meshSettings, lightSettings,
                decalSettings, volumeSettings, splatSettings, castsShadows, rateOverDistance);
    }

    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                             double delay, double duration, boolean looping, int maximumParticles,
                             ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                             ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                             ScalarFunction rotation, ScalarFunction angularVelocity, ScalarFunction startSpeed, Vec3 startDirection,
                             ColorFunction startColor, Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                             List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife, Shape shape,
                             ScalarFunction sizeOverLife, ColorFunction colorOverLife, List<ScalarFunction> customData,
                             Noise noise, Integration integration, Randomization randomization, UvMode uvMode, double uvDistance,
                             int atlasColumns, int atlasRows, ScalarFunction atlasFrame, int subdivisions, int widthSegments,
                             boolean attachHead, RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                             int atlasRow, double macroUVRadius, @Nullable SpriteCutout spriteCutout, @Nullable MeshRendererSettings meshSettings,
                             @Nullable LightRendererSettings lightSettings, @Nullable DecalRendererSettings decalSettings,
                             @Nullable VolumeRendererSettings volumeSettings, boolean castsShadows) {
        this(name, renderer, material, localSpace, delay, duration, looping, maximumParticles, rate, distanceSpacing, movementThreshold,
                bursts, lifetime, mass, width, height, rotation, angularVelocity, startSpeed, startDirection, startColor,
                velocityMinimum, velocityMaximum, acceleration, drag, velocityOverLife, forceOverLife, shape, sizeOverLife, colorOverLife,
                customData, noise, integration, randomization, uvMode, uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions,
                widthSegments, attachHead, ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, atlasRow, macroUVRadius,
                spriteCutout, meshSettings, lightSettings, decalSettings, volumeSettings, null, castsShadows);
    }

    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                             double delay, double duration, boolean looping, int maximumParticles,
                             ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                             ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                             ScalarFunction rotation, ScalarFunction angularVelocity, ScalarFunction startSpeed, Vec3 startDirection,
                             ColorFunction startColor, Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                             List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife, Shape shape,
                             ScalarFunction sizeOverLife, ColorFunction colorOverLife, List<ScalarFunction> customData,
                             Noise noise, Integration integration, Randomization randomization, UvMode uvMode, double uvDistance,
                             int atlasColumns, int atlasRows, ScalarFunction atlasFrame, int subdivisions, int widthSegments,
                             boolean attachHead, RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                             int atlasRow, double macroUVRadius, @Nullable SpriteCutout spriteCutout, @Nullable MeshRendererSettings meshSettings,
                             @Nullable LightRendererSettings lightSettings, @Nullable DecalRendererSettings decalSettings,
                             @Nullable VolumeRendererSettings volumeSettings, @Nullable SplatRendererSettings splatSettings,
                             boolean castsShadows) {
        this(name, renderer, material, localSpace, delay, duration, looping, maximumParticles, rate, distanceSpacing, movementThreshold,
                bursts, lifetime, mass, width, height, rotation, angularVelocity, startSpeed, startDirection, startColor,
                velocityMinimum, velocityMaximum, acceleration, drag, velocityOverLife, forceOverLife, shape, sizeOverLife, colorOverLife,
                customData, noise, integration, randomization, uvMode, uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions,
                widthSegments, attachHead, ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, atlasRow, macroUVRadius,
                spriteCutout, meshSettings, lightSettings, decalSettings, volumeSettings, splatSettings, castsShadows, null);
    }

    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                             double delay, double duration, boolean looping, int maximumParticles,
                             ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                             ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                             ScalarFunction rotation, ScalarFunction angularVelocity, ScalarFunction startSpeed, Vec3 startDirection,
                             ColorFunction startColor, Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                             List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife, Shape shape,
                             ScalarFunction sizeOverLife, ColorFunction colorOverLife, List<ScalarFunction> customData,
                             Noise noise, Integration integration, Randomization randomization, UvMode uvMode, double uvDistance,
                             int atlasColumns, int atlasRows, ScalarFunction atlasFrame, int subdivisions, int widthSegments,
                             boolean attachHead, RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                             int atlasRow, double macroUVRadius, @Nullable SpriteCutout spriteCutout, @Nullable MeshRendererSettings meshSettings,
                             @Nullable LightRendererSettings lightSettings, @Nullable DecalRendererSettings decalSettings,
                             @Nullable VolumeRendererSettings volumeSettings) {
        this(name, renderer, material, localSpace, delay, duration, looping, maximumParticles, rate, distanceSpacing, movementThreshold,
                bursts, lifetime, mass, width, height, rotation, angularVelocity, startSpeed, startDirection, startColor,
                velocityMinimum, velocityMaximum, acceleration, drag, velocityOverLife, forceOverLife, shape, sizeOverLife, colorOverLife,
                customData, noise, integration, randomization, uvMode, uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions,
                widthSegments, attachHead, ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, atlasRow, macroUVRadius,
                spriteCutout, meshSettings, lightSettings, decalSettings, volumeSettings, null, true);
    }

    public enum Renderer {
        SPRITE, RIBBON, BEAM, MESH, LIGHT, DECAL, VOLUME, SPLAT, COMPONENT, SIMULATION;
        public boolean usesMaterial() { return this != LIGHT && this != COMPONENT && this != SIMULATION && this != SPLAT; }
    }
    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                             double delay, double duration, boolean looping, int maximumParticles,
                             ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                             ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                             ScalarFunction rotation, ScalarFunction angularVelocity, ScalarFunction startSpeed, Vec3 startDirection,
                             ColorFunction startColor, Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                             List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife, Shape shape,
                             ScalarFunction sizeOverLife, ColorFunction colorOverLife, List<ScalarFunction> customData,
                             Noise noise, Integration integration, Randomization randomization, UvMode uvMode, double uvDistance,
                             int atlasColumns, int atlasRows, ScalarFunction atlasFrame, int subdivisions, int widthSegments,
                             boolean attachHead, RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                             int atlasRow, double macroUVRadius, @Nullable SpriteCutout spriteCutout, @Nullable MeshRendererSettings meshSettings,
                             @Nullable LightRendererSettings lightSettings, @Nullable DecalRendererSettings decalSettings) {
        this(name, renderer, material, localSpace, delay, duration, looping, maximumParticles, rate, distanceSpacing, movementThreshold,
                bursts, lifetime, mass, width, height, rotation, angularVelocity, startSpeed, startDirection, startColor,
                velocityMinimum, velocityMaximum, acceleration, drag, velocityOverLife, forceOverLife, shape, sizeOverLife, colorOverLife,
                customData, noise, integration, randomization, uvMode, uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions,
                widthSegments, attachHead, ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, atlasRow, macroUVRadius,
                spriteCutout, meshSettings, lightSettings, decalSettings, null);
    }
    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                                double delay, double duration, boolean looping, int maximumParticles,
                                ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                                ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                                ScalarFunction rotation, ScalarFunction angularVelocity,
                                ScalarFunction startSpeed, Vec3 startDirection, ColorFunction startColor,
                                Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                                List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife,
                                Shape shape, ScalarFunction sizeOverLife, ColorFunction colorOverLife,
                                List<ScalarFunction> customData, Noise noise, Integration integration, Randomization randomization,
                                UvMode uvMode, double uvDistance, int atlasColumns, int atlasRows,
                                ScalarFunction atlasFrame, int subdivisions, int widthSegments, boolean attachHead,
                                RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                                int atlasRow, double macroUVRadius, @Nullable SpriteCutout spriteCutout,
                                @Nullable MeshRendererSettings meshSettings, @Nullable LightRendererSettings lightSettings) {
        this(name, renderer, material, localSpace, delay, duration, looping, maximumParticles, rate, distanceSpacing, movementThreshold, bursts, lifetime, mass, width, height, rotation, angularVelocity, startSpeed, startDirection, startColor, velocityMinimum, velocityMaximum, acceleration, drag, velocityOverLife, forceOverLife, shape, sizeOverLife, colorOverLife, customData, noise, integration, randomization, uvMode, uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions, widthSegments, attachHead, ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, atlasRow, macroUVRadius, spriteCutout, meshSettings, lightSettings, null);
    }

    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                                double delay, double duration, boolean looping, int maximumParticles,
                                ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                                ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                                ScalarFunction rotation, ScalarFunction angularVelocity,
                                ScalarFunction startSpeed, Vec3 startDirection, ColorFunction startColor,
                                Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                                List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife,
                                Shape shape, ScalarFunction sizeOverLife, ColorFunction colorOverLife,
                                List<ScalarFunction> customData, Noise noise, Integration integration, Randomization randomization,
                                UvMode uvMode, double uvDistance, int atlasColumns, int atlasRows,
                                ScalarFunction atlasFrame, int subdivisions, int widthSegments, boolean attachHead,
                                RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                                int atlasRow, double macroUVRadius, @Nullable SpriteCutout spriteCutout,
                                @Nullable MeshRendererSettings meshSettings) {
        this(name, renderer, material, localSpace, delay, duration, looping, maximumParticles, rate, distanceSpacing,
                movementThreshold, bursts, lifetime, mass, width, height, rotation, angularVelocity, startSpeed,
                startDirection, startColor, velocityMinimum, velocityMaximum, acceleration, drag, velocityOverLife,
                forceOverLife, shape, sizeOverLife, colorOverLife, customData, noise, integration, randomization,
                uvMode, uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions, widthSegments, attachHead,
                ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, atlasRow, macroUVRadius, spriteCutout,
                meshSettings, null);
    }
    public enum UvMode { STRETCH, DISTANCE }
    public enum ShapeType { POINT, BOX, SPHERE, CYLINDER, CONE, CIRCLE, HEMISPHERE, EDGE, BOX_EDGE, DONUT, MESH }
    public enum NoiseMode { DISPLACEMENT, VECTOR_FIELD }
    public enum Integration { ANALYTIC, NIAGARA }
    public enum SortMode { NONE, DISTANCE, VIEW_DEPTH, CUSTOM_ASCENDING, CUSTOM_DESCENDING }
    public record Randomization(int dragSeed, int dragContext, boolean independentColor, int colorSeed, int colorContext) { }
    public record Burst(double time, int minimum, int maximum, int cycles, double interval, double probability,
                        @Nullable ScalarFunction countCurve) {
        public Burst(double time, int minimum, int maximum, int cycles, double interval, double probability) {
            this(time, minimum, maximum, cycles, interval, probability, null);
        }

        public Burst(double time, int minimum, int maximum, int cycles, double interval) {
            this(time, minimum, maximum, cycles, interval, 1);
        }

        public Burst {
            if (!Double.isFinite(time) || time < 0 || minimum < 0 || maximum < minimum
                    || cycles < 0 || !Double.isFinite(interval) || interval < 0 || cycles == 0 && interval == 0
                    || !Double.isFinite(probability) || probability < 0 || probability > 1
                    || countCurve != null && (minimum != 0 || maximum != 0))
                throw new IllegalArgumentException("Invalid emission burst");
        }
    }
    public record Shape(ShapeType type, Vec3 size, boolean surface, double angle, @Nullable UnityShapeSettings unity) {
        public Shape {
            if (type == ShapeType.DONUT && (unity == null || unity.donut() == null))
                throw new IllegalArgumentException("Donut shape requires its ring and tube radii");
            if (type == ShapeType.MESH && (unity == null || unity.mesh() == null))
                throw new IllegalArgumentException("Mesh shape requires authored geometry");
        }

        public Shape(ShapeType type, Vec3 size, boolean surface, double angle) {
            this(type, size, surface, angle, null);
        }
    }
    public record Noise(Vec3 strength, double frequency, Vec3 scroll, NoiseMode mode, FieldSampling field) {
        public Noise(Vec3 strength, double frequency, Vec3 scroll, NoiseMode mode) { this(strength, frequency, scroll, mode, null); }
        public Noise {
            if (mode == null || mode == NoiseMode.VECTOR_FIELD && field == null || mode != NoiseMode.VECTOR_FIELD && field != null)
                throw new IllegalArgumentException("Vector field reference does not match noise mode");
        }
    }

    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                                double delay, double duration, boolean looping, int maximumParticles,
                                ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                                ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                                ScalarFunction rotation, ScalarFunction angularVelocity,
                                ScalarFunction startSpeed, Vec3 startDirection, ColorFunction startColor,
                                Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                                List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife,
                                Shape shape, ScalarFunction sizeOverLife, ColorFunction colorOverLife,
                                List<ScalarFunction> customData, Noise noise, Integration integration, Randomization randomization,
                                UvMode uvMode, double uvDistance, int atlasColumns, int atlasRows,
                                ScalarFunction atlasFrame, int subdivisions, int widthSegments, boolean attachHead,
                                RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                                int atlasRow, double macroUVRadius, @Nullable SpriteCutout spriteCutout) {
        this(
                name, renderer, material, localSpace, delay, duration,
                looping, maximumParticles, rate, distanceSpacing, movementThreshold, bursts,
                lifetime, mass, width, height, rotation, angularVelocity,
                startSpeed, startDirection, startColor, velocityMinimum, velocityMaximum, acceleration,
                drag, velocityOverLife, forceOverLife, shape, sizeOverLife, colorOverLife,
                customData, noise, integration, randomization, uvMode, uvDistance,
                atlasColumns, atlasRows, atlasFrame, subdivisions, widthSegments, attachHead,
                ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, atlasRow, macroUVRadius,
                spriteCutout, null);
    }

    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                                double delay, double duration, boolean looping, int maximumParticles,
                                ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                                ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                                ScalarFunction rotation, ScalarFunction angularVelocity,
                                ScalarFunction startSpeed, Vec3 startDirection, ColorFunction startColor,
                                Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                                List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife,
                                Shape shape, ScalarFunction sizeOverLife, ColorFunction colorOverLife,
                                List<ScalarFunction> customData, Noise noise, Integration integration, Randomization randomization,
                                UvMode uvMode, double uvDistance, int atlasColumns, int atlasRows,
                                ScalarFunction atlasFrame, int subdivisions, int widthSegments, boolean attachHead,
                                RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode) {
        this(
                name, renderer, material, localSpace, delay, duration, looping,
                maximumParticles, rate, distanceSpacing, movementThreshold, bursts, lifetime, mass,
                width, height, rotation, angularVelocity, startSpeed, startDirection, startColor,
                velocityMinimum, velocityMaximum, acceleration, drag, velocityOverLife, forceOverLife, shape,
                sizeOverLife, colorOverLife, customData, noise, integration, randomization, uvMode,
                uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions, widthSegments, attachHead,
                ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, -1, 0);
    }

    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                                double delay, double duration, boolean looping, int maximumParticles,
                                ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                                ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                                ScalarFunction rotation, ScalarFunction angularVelocity,
                                ScalarFunction startSpeed, Vec3 startDirection, ColorFunction startColor,
                                Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                                List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife,
                                Shape shape, ScalarFunction sizeOverLife, ColorFunction colorOverLife,
                                List<ScalarFunction> customData, Noise noise, Integration integration, Randomization randomization,
                                UvMode uvMode, double uvDistance, int atlasColumns, int atlasRows,
                                ScalarFunction atlasFrame, int subdivisions, int widthSegments, boolean attachHead,
                                RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                                int atlasRow) {
        this(name, renderer, material, localSpace, delay, duration, looping, maximumParticles, rate, distanceSpacing,
                movementThreshold, bursts, lifetime, mass, width, height, rotation, angularVelocity, startSpeed,
                startDirection, startColor, velocityMinimum, velocityMaximum, acceleration, drag, velocityOverLife,
                forceOverLife, shape, sizeOverLife, colorOverLife, customData, noise, integration, randomization,
                uvMode, uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions, widthSegments, attachHead,
                ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, atlasRow, 0);
    }

    public EmitterDefinition(String name, Renderer renderer, String material, boolean localSpace,
                                double delay, double duration, boolean looping, int maximumParticles,
                                ScalarFunction rate, double distanceSpacing, double movementThreshold, List<Burst> bursts,
                                ScalarFunction lifetime, ScalarFunction mass, ScalarFunction width, ScalarFunction height,
                                ScalarFunction rotation, ScalarFunction angularVelocity,
                                ScalarFunction startSpeed, Vec3 startDirection, ColorFunction startColor,
                                Vec3 velocityMinimum, Vec3 velocityMaximum, Vec3 acceleration, ScalarFunction drag,
                                List<ScalarFunction> velocityOverLife, List<ScalarFunction> forceOverLife,
                                Shape shape, ScalarFunction sizeOverLife, ColorFunction colorOverLife,
                                List<ScalarFunction> customData, Noise noise, Integration integration, Randomization randomization,
                                UvMode uvMode, double uvDistance, int atlasColumns, int atlasRows,
                                ScalarFunction atlasFrame, int subdivisions, int widthSegments, boolean attachHead,
                                RibbonSettings ribbonSettings, boolean scaleSizeWithOwner, int sortOrder, SortMode sortMode,
                                int atlasRow, double macroUVRadius) {
        this(name, renderer, material, localSpace, delay, duration, looping, maximumParticles, rate, distanceSpacing,
                movementThreshold, bursts, lifetime, mass, width, height, rotation, angularVelocity, startSpeed,
                startDirection, startColor, velocityMinimum, velocityMaximum, acceleration, drag, velocityOverLife,
                forceOverLife, shape, sizeOverLife, colorOverLife, customData, noise, integration, randomization,
                uvMode, uvDistance, atlasColumns, atlasRows, atlasFrame, subdivisions, widthSegments, attachHead,
                ribbonSettings, scaleSizeWithOwner, sortOrder, sortMode, atlasRow, macroUVRadius, null);
    }

    public EmitterDefinition {
        bursts = List.copyOf(bursts);
        customData = List.copyOf(customData);
        velocityOverLife = List.copyOf(velocityOverLife);
        forceOverLife = List.copyOf(forceOverLife);
        if (name.isBlank() || renderer == null || material == null || renderer.usesMaterial() && material.isBlank()
                || uvMode == null || sortMode == null || shape.type() == null || mass == null || integration == null || randomization == null
                || !Double.isFinite(delay) || delay < 0 || !Double.isFinite(duration) || duration <= 0
                || maximumParticles < 1 || maximumParticles > 1000000
                || !Double.isFinite(distanceSpacing) || distanceSpacing < 0
                || !Double.isFinite(movementThreshold) || movementThreshold <= 0 || drag == null
                || !Double.isFinite(uvDistance) || uvDistance <= 0
                || atlasColumns < 1 || atlasRows < 1 || (long)atlasColumns * atlasRows > 65536
                || atlasRow < -1 || atlasRow >= atlasRows
                || !Double.isFinite(macroUVRadius) || spriteCutout != null && renderer != Renderer.SPRITE
                || (renderer == Renderer.MESH) != (meshSettings != null)
                || (renderer == Renderer.LIGHT) != (lightSettings != null)
                || renderer == Renderer.LIGHT && integration != Integration.NIAGARA
                || (renderer == Renderer.DECAL) != (decalSettings != null)
                || renderer == Renderer.DECAL && integration != Integration.NIAGARA
                || (renderer == Renderer.VOLUME) != (volumeSettings != null)
                || renderer == Renderer.VOLUME && integration != Integration.NIAGARA
                || (renderer == Renderer.SPLAT) != (splatSettings != null)
                || renderer == Renderer.SPLAT && integration != Integration.NIAGARA
                || renderer == Renderer.COMPONENT && integration != Integration.NIAGARA
                || renderer == Renderer.SIMULATION && integration != Integration.ANALYTIC
                || subdivisions < 1 || subdivisions > 64 || widthSegments < 1 || widthSegments > 64 || customData.size() != 4
                || velocityOverLife.size() != 3 || forceOverLife.size() != 3)
            throw new IllegalArgumentException("Invalid emitter " + name);
        for (Vec3 vector : List.of(velocityMinimum, velocityMaximum, acceleration, startDirection, shape.size(), noise.strength(), noise.scroll())) {
            if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z))
                throw new IllegalArgumentException("Non-finite emitter vector: " + name);
        }
        if (!Double.isFinite(shape.angle()) || !Double.isFinite(noise.frequency()))
            throw new IllegalArgumentException("Non-finite emitter parameters: " + name);
        boolean unityCone = shape.unity() != null && shape.unity().cone() != null;
        boolean invalidCone = shape.type() == ShapeType.CONE && (unityCone
                ? shape.angle() < 0 || shape.angle() > Math.PI * .5
                : Math.abs(shape.angle()) >= Math.PI * .5 || shape.size().x + shape.size().y * Math.tan(shape.angle()) < 0);
        if (shape.size().x < 0 || shape.size().y < 0 || shape.size().z < 0 || invalidCone)
            throw new IllegalArgumentException("Invalid emitter shape: " + name);
    }
}
