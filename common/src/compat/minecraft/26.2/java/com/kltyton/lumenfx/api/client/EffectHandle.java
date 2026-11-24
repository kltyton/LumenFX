package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl;
import com.kltyton.lumenfx.api.client.component.PrimitiveOwnerVisibility;

import com.kltyton.lumenfx.effect.render.depth.CustomDepthStencil;
import com.kltyton.lumenfx.effect.render.material.PrimitiveCustomData;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionInstance;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSocketInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAutoAttachmentParameters;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraWeightedInt;
import com.kltyton.lumenfx.api.client.parameter.NiagaraEmitterOverrides;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution.NiagaraIntDistribution;
import com.kltyton.lumenfx.api.client.parameter.NiagaraMovieRenderSample;
import com.kltyton.lumenfx.api.client.parameter.NiagaraCollisionWorld;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPropertyInterfaceInputs;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraVirtualTextureInputs;
import com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState;
import net.minecraft.resources.Identifier;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.ParameterCollectionValues;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.api.client.parameter.NiagaraParticleExport;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Overrides;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentDefaults.OcclusionMode;
import com.kltyton.lumenfx.effect.niagara.component.NiagaraTranslucencySort;
import com.kltyton.lumenfx.effect.niagara.NiagaraTiming.Warmup;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Playback;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import com.kltyton.lumenfx.effect.niagara.parameter.UserParameterNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.Reference;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;
import com.kltyton.lumenfx.effect.render.light.PrimitiveLighting;

/** Numeric attachment updates can be published without accessing a world from the renderer. */
public final class EffectHandle implements AutoCloseable {
    public enum ParameterType {
        FLOAT(1, 0), FLOAT2(2, 0), FLOAT3(3, 0), FLOAT4(4, 0),
        INT(1, 1), INT2(2, 1), INT3(3, 1), INT4(4, 1),
        BOOL(1, 2), BOOL2(2, 2), BOOL3(3, 2), BOOL4(4, 2),
        HALF(1, 3), HALF2(2, 3), HALF3(3, 3), HALF4(4, 3), POSITION(3, 4), STATIC_MESH_ID(2, 5);
        private final int components, storage;
        ParameterType(int components, int storage) { this.components = components; this.storage = storage; }
        public int components() { return components; }
        public int storage() { return storage; }
    }

    public enum ArrayType {
        FLOAT(1), FLOAT2(2), FLOAT3(3), POSITION(3), FLOAT4(4), COLOR(4), QUAT(4), MATRIX(16),
        INT32(1), UINT8(1), BOOL(1), NIAGARA_ID(2);
        private final int components;
        ArrayType(int components) { this.components = components; }
        public int components() { return components; }
    }

    /** Array elements are contiguous 32-bit Niagara VM words in source component order. */
    public record ArrayParameter(ArrayType type, int[] words) {
        public ArrayParameter {
            if (type == null || words == null || words.length % type.components() != 0)
                throw new IllegalArgumentException("Invalid Niagara array parameter layout");
            words = words.clone();
        }
        @Override public int[] words() { return words.clone(); }

        public static boolean matchesName(String left, String right) {
            return UserParameterNames.equal(left, right);
        }
        public static @Nullable ArrayParameter find(Map<String, ArrayParameter> values, String name) {
            ArrayParameter exact = values.get(name);
            if (exact != null) return exact;
            for (var entry : values.entrySet()) if (matchesName(entry.getKey(), name)) return entry.getValue();
            return null;
        }
        public static @Nullable ArrayParameter latest(Map<String, ArrayParameter> values, Collection<String> names) {
            ArrayParameter result = null;
            for (var entry : values.entrySet()) for (String name : names) if (matchesName(entry.getKey(), name)) {
                result = entry.getValue();
                break;
            }
            return result;
        }
        private static Map<String, ArrayParameter> snapshot(Map<String, ArrayParameter> source) {
            var copy = new LinkedHashMap<String, ArrayParameter>();
            source.forEach((name, value) -> copy.put(Objects.requireNonNull(name), Objects.requireNonNull(value)));
            return Collections.unmodifiableMap(copy);
        }
    }

    /** Scalar and vector values use their declared Niagara numeric type. */
    public record ParameterValue(ParameterType type, long[] words) {
        public ParameterValue {
            if (type == null || words == null || words.length != type.components())
                throw new IllegalArgumentException("Parameter word count does not match type");
            words = words.clone();
            for (int i = 0; i < words.length; i++) {
                if (type.storage() == 3 && (words[i] & ~0xffffL) != 0)
                    throw new IllegalArgumentException("Invalid half parameter bits");
                if (type.storage() == 2 && words[i] != 0 && words[i] != 1)
                    throw new IllegalArgumentException("Invalid bool parameter word");
                if (type == ParameterType.POSITION && !Double.isFinite(Double.longBitsToDouble(words[i])))
                    throw new IllegalArgumentException("Position parameter must contain finite world coordinates");
            }
        }
        @Override public long[] words() { return words.clone(); }
        public static ParameterValue ofFloat(float value) {
            return new ParameterValue(ParameterType.FLOAT, new long[] {Float.floatToRawIntBits(value)});
        }
        /** Absolute world coordinates in source XYZ centimetres; conversion to a simulation tile happens per frame. */
        public static ParameterValue ofSourcePosition(double x, double y, double z) {
            return new ParameterValue(ParameterType.POSITION, new long[] {
                    Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y), Double.doubleToRawLongBits(z)});
        }
        public static ParameterValue ofFloats(float... values) {
            ParameterType type = switch (values.length) { case 1 -> ParameterType.FLOAT; case 2 -> ParameterType.FLOAT2;
                case 3 -> ParameterType.FLOAT3; case 4 -> ParameterType.FLOAT4; default -> throw new IllegalArgumentException("Float vector needs 1-4 components"); };
            long[] words = new long[values.length];
            for (int i = 0; i < values.length; i++) words[i] = Float.floatToRawIntBits(values[i]);
            return new ParameterValue(type, words);
        }
        public static ParameterValue ofInts(int... values) {
            ParameterType type = switch (values.length) { case 1 -> ParameterType.INT; case 2 -> ParameterType.INT2;
                case 3 -> ParameterType.INT3; case 4 -> ParameterType.INT4; default -> throw new IllegalArgumentException("Integer vector needs 1-4 components"); };
            long[] words = new long[values.length];
            for (int i = 0; i < values.length; i++) words[i] = values[i];
            return new ParameterValue(type, words);
        }
        public static ParameterValue ofBooleans(boolean... values) {
            ParameterType type = switch (values.length) { case 1 -> ParameterType.BOOL; case 2 -> ParameterType.BOOL2;
                case 3 -> ParameterType.BOOL3; case 4 -> ParameterType.BOOL4; default -> throw new IllegalArgumentException("Boolean vector needs 1-4 components"); };
            long[] words = new long[values.length];
            for (int i = 0; i < values.length; i++) words[i] = values[i] ? 1 : 0;
            return new ParameterValue(type, words);
        }
        public static ParameterValue ofHalves(short... values) {
            ParameterType type = switch (values.length) { case 1 -> ParameterType.HALF; case 2 -> ParameterType.HALF2;
                case 3 -> ParameterType.HALF3; case 4 -> ParameterType.HALF4; default -> throw new IllegalArgumentException("Half vector needs 1-4 components"); };
            long[] words = new long[values.length];
            for (int i = 0; i < values.length; i++) words[i] = Short.toUnsignedInt(values[i]);
            return new ParameterValue(type, words);
        }
        public float floatScalar() {
            if (type != ParameterType.FLOAT) throw new IllegalStateException("Parameter is not a scalar float");
            return Float.intBitsToFloat((int)words[0]);
        }
    }

    public record Pose(Vec3 position, float x, float y, float z, float w, float scale, float scaleY, float scaleZ) {
        public Pose(Vec3 position, float x, float y, float z, float w, float scale) {
            this(position, x, y, z, w, positiveUniformScale(scale), scale, scale);
        }
        public Pose {
            float length = x * x + y * y + z * z + w * w;
            if (!finite(position) || !Float.isFinite(length) || length <= 0
                    || !Float.isFinite(scale) || !Float.isFinite(scaleY) || !Float.isFinite(scaleZ))
                throw new IllegalArgumentException("Invalid effect pose");
            float factor = (float)(1 / Math.sqrt(length));
            x *= factor; y *= factor; z *= factor; w *= factor;
        }
        public static Pose at(Vec3 position) { return new Pose(position, 0, 0, 0, 1, 1); }
        public Pose withPosition(Vec3 position) { return new Pose(position, x, y, z, w, scale, scaleY, scaleZ); }
        public float scaleX() { return scale; }
        public boolean uniformScale() { return scale == scaleY && scale == scaleZ; }
        private static float positiveUniformScale(float value) {
            if (!Float.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid effect pose");
            return value;
        }
        public Vec3 transformDirection(Vec3 value) {
            if (!uniformScale()) {
                double vx = value.x * scale, vy = value.y * scaleY, vz = value.z * scaleZ;
                double tx = 2 * (y * vz - z * vy), ty = 2 * (z * vx - x * vz), tz = 2 * (x * vy - y * vx);
                return new Vec3(vx + w * tx + y * tz - z * ty, vy + w * ty + z * tx - x * tz,
                        vz + w * tz + x * ty - y * tx);
            }
            var result = new Quaternionf(x, y, z, w).transform((float)value.x, (float)value.y, (float)value.z, new org.joml.Vector3f());
            return new Vec3(result.x, result.y, result.z).scale(scale);
        }
        public Vec3 transformPosition(Vec3 value) { return transformDirection(value).add(position); }

        /** Transforms one position triple in place without narrowing local coordinates to floats. */
        public void transformPosition(double[] values, int offset) {
            double vx = values[offset] * scale, vy = values[offset + 1] * scaleY, vz = values[offset + 2] * scaleZ;
            double tx = 2 * (y * vz - z * vy), ty = 2 * (z * vx - x * vz), tz = 2 * (x * vy - y * vx);
            values[offset] = vx + w * tx + y * tz - z * ty + position.x;
            values[offset + 1] = vy + w * ty + z * tx - x * tz + position.y;
            values[offset + 2] = vz + w * tz + x * ty - y * tx + position.z;
        }
    }
    /** Sequence advances when the caller publishes a new attachment pose. */
    public record Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                        float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                        Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                        Map<String, RendererParameter> rendererParameters, boolean receivesDecals,
                        Map<String, TextureParameter> textureParameters, boolean firstPerson, PrimitiveLighting primitiveLighting,
                        CustomDepthStencil customDepthStencil, PrimitiveCustomData customPrimitiveData,
                        @Nullable Velocity ownerVelocity, NiagaraTranslucencySort niagaraTranslucencySort,
                        boolean renderInMainPass) {
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                     Map<String, RendererParameter> rendererParameters, boolean receivesDecals,
                     Map<String, TextureParameter> textureParameters, boolean firstPerson, PrimitiveLighting primitiveLighting,
                     CustomDepthStencil customDepthStencil, PrimitiveCustomData customPrimitiveData, @Nullable Velocity ownerVelocity,
                     NiagaraTranslucencySort niagaraTranslucencySort) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters,
                    firstPerson, primitiveLighting, customDepthStencil, customPrimitiveData, ownerVelocity,
                    niagaraTranslucencySort, true);
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                     Map<String, RendererParameter> rendererParameters, boolean receivesDecals,
                     Map<String, TextureParameter> textureParameters, boolean firstPerson, PrimitiveLighting primitiveLighting,
                     CustomDepthStencil customDepthStencil, PrimitiveCustomData customPrimitiveData, @Nullable Velocity ownerVelocity) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters,
                    firstPerson, primitiveLighting, customDepthStencil, customPrimitiveData, ownerVelocity, NiagaraTranslucencySort.DEFAULT);
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                     Map<String, RendererParameter> rendererParameters, boolean receivesDecals,
                     Map<String, TextureParameter> textureParameters, boolean firstPerson, PrimitiveLighting primitiveLighting,
                     CustomDepthStencil customDepthStencil, PrimitiveCustomData customPrimitiveData) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters,
                    firstPerson, primitiveLighting, customDepthStencil, customPrimitiveData, null);
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                     Map<String, RendererParameter> rendererParameters, boolean receivesDecals,
                     Map<String, TextureParameter> textureParameters, boolean firstPerson, PrimitiveLighting primitiveLighting,
                     CustomDepthStencil customDepthStencil) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters,
                    firstPerson, primitiveLighting, customDepthStencil, PrimitiveCustomData.EMPTY);
        }

        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                     Map<String, RendererParameter> rendererParameters, boolean receivesDecals,
                     Map<String, TextureParameter> textureParameters, boolean firstPerson, PrimitiveLighting primitiveLighting) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters,
                    firstPerson, primitiveLighting, CustomDepthStencil.DISABLED);
        }

        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                     Map<String, RendererParameter> rendererParameters, boolean receivesDecals,
                     Map<String, TextureParameter> textureParameters, boolean firstPerson) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters,
                    firstPerson, PrimitiveLighting.DEFAULT);
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                     Map<String, RendererParameter> rendererParameters, boolean receivesDecals,
                     Map<String, TextureParameter> textureParameters) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters, false);
        }
        public Input {
            if (!finite(actorPosition)) throw new IllegalArgumentException("Invalid effect actor position");
            if (ownerVelocity != null && (!Double.isFinite(ownerVelocity.x()) || !Double.isFinite(ownerVelocity.y())
                    || !Double.isFinite(ownerVelocity.z()))) throw new IllegalArgumentException("Invalid Niagara owner velocity");
            parameters = Map.copyOf(parameters);
            arrayParameters = ArrayParameter.snapshot(arrayParameters);
            rendererParameters = normalizeRendererParameters(rendererParameters);
            textureParameters = Map.copyOf(textureParameters);
            Objects.requireNonNull(customPrimitiveData);
            Objects.requireNonNull(niagaraTranslucencySort);
        }
        private static Map<String, RendererParameter> normalizeRendererParameters(Map<String, RendererParameter> source) {
            if (source.isEmpty()) return Map.of();
            var normalized = new LinkedHashMap<String, RendererParameter>(source.size());
            source.forEach((key, value) -> {
                if (key == null || value == null) throw new IllegalArgumentException("Invalid Niagara renderer object parameter");
                normalized.keySet().removeIf(existing -> SourceNames.equal(existing, key));
                normalized.put(key, value);
            });
            return Collections.unmodifiableMap(normalized);
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                     Map<String, RendererParameter> rendererParameters, boolean receivesDecals) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, Map.of());
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit,
                     Map<String, RendererParameter> rendererParameters) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, true);
        }
        public Input withRendererDefaults(Map<String, RendererParameter> defaults) {
            if (defaults.isEmpty()) return this;
            var merged = new LinkedHashMap<String, RendererParameter>(defaults.size() + rendererParameters.size());
            defaults.forEach((key, value) -> {
                merged.keySet().removeIf(existing -> SourceNames.equal(existing, key));
                merged.put(key, value);
            });
            rendererParameters.forEach((key, value) -> {
                merged.keySet().removeIf(existing -> SourceNames.equal(existing, key));
                merged.put(key, value);
            });
            return new Input(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha,
                    parameters, arrayParameters, actorPosition, actorPositionExplicit, merged, receivesDecals, textureParameters, firstPerson,
                    primitiveLighting, customDepthStencil, customPrimitiveData, ownerVelocity, niagaraTranslucencySort,
                    renderInMainPass);
        }
        public Input withPrimitiveLighting(PrimitiveLighting lighting) {
            if (primitiveLighting.equals(lighting)) return this;
            return new Input(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters, firstPerson,
                    lighting, customDepthStencil, customPrimitiveData, ownerVelocity, niagaraTranslucencySort,
                    renderInMainPass);
        }
        public Input withCustomDepthStencil(CustomDepthStencil depth) {
            if (customDepthStencil.equals(depth)) return this;
            return new Input(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters, firstPerson,
                    primitiveLighting, depth, customPrimitiveData, ownerVelocity, niagaraTranslucencySort,
                    renderInMainPass);
        }
        public Input withCustomPrimitiveData(PrimitiveCustomData data) {
            if (customPrimitiveData == data) return this;
            return new Input(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters, firstPerson,
                    primitiveLighting, customDepthStencil, data, ownerVelocity, niagaraTranslucencySort,
                    renderInMainPass);
        }
        public Input withNiagaraTranslucencySort(NiagaraTranslucencySort sort) {
            if (niagaraTranslucencySort.equals(sort)) return this;
            return new Input(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters, firstPerson,
                    primitiveLighting, customDepthStencil, customPrimitiveData, ownerVelocity, sort,
                    renderInMainPass);
        }
        public Input withRenderInMainPass(boolean mainPass) {
            if (renderInMainPass == mainPass) return this;
            return new Input(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters,
                    firstPerson, primitiveLighting, customDepthStencil, customPrimitiveData, ownerVelocity,
                    niagaraTranslucencySort, mainPass);
        }
        public Input withPose(Pose pose) {
            if (this.pose.equals(pose)) return this;
            return withPose(pose, sequence, actorPosition, actorPositionExplicit);
        }
        public Input withColor(float red, float green, float blue, float alpha) {
            if (this.red == red && this.green == green && this.blue == blue && this.alpha == alpha) return this;
            return new Input(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha,
                    parameters, arrayParameters, actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters,
                    firstPerson, primitiveLighting, customDepthStencil, customPrimitiveData, ownerVelocity, niagaraTranslucencySort,
                    renderInMainPass);
        }
        private Input withPose(Pose pose, long nextSequence, Vec3 actorPosition, boolean actorPositionExplicit) {
            return withPose(pose, nextSequence, actorPosition, actorPositionExplicit, ownerVelocity);
        }
        private Input withPose(Pose pose, long nextSequence, Vec3 actorPosition, boolean actorPositionExplicit,
                               @Nullable Velocity velocity) {
            return new Input(pose, beamEnd, nextSequence, renderTracked, red, green, blue, alpha,
                    parameters, arrayParameters, actorPosition, actorPositionExplicit, rendererParameters, receivesDecals, textureParameters,
                    firstPerson, primitiveLighting, customDepthStencil, customPrimitiveData, velocity, niagaraTranslucencySort,
                    renderInMainPass);
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters, Vec3 actorPosition, boolean actorPositionExplicit) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    actorPosition, actorPositionExplicit, Map.of());
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters,
                     Map<String, ArrayParameter> arrayParameters) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, arrayParameters,
                    pose.position(), false);
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha, Map<String, ParameterValue> parameters) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, parameters, Map.of());
        }
        public Input(Pose pose, Vec3 beamEnd, long sequence, boolean renderTracked,
                     float red, float green, float blue, float alpha) {
            this(pose, beamEnd, sequence, renderTracked, red, green, blue, alpha, Map.of());
        }
    }

    private final AtomicReference<Input> input;
    private volatile @Nullable PrimitiveLighting primitiveLightingOverride;
    private volatile @Nullable PrimitiveOwnerVisibility niagaraOwnerVisibility;
    private volatile @Nullable NiagaraTranslucencySort niagaraTranslucencySortOverride;
    private volatile Set<UUID> niagaraViewOwners = Set.of();
    private volatile @Nullable CustomDepthStencil customDepthStencilOverride;
    private final AtomicReference<ParameterCollectionValues> collectionOverrides = new AtomicReference<>(ParameterCollectionValues.EMPTY);
    private final AtomicReference<Map<Key, Reference>> dataInterfaceParameters = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<Key, Reference>> objectParameters = new AtomicReference<>(Map.of());
    private final AtomicReference<ObjectPropertyInputs> propertyInputs = new AtomicReference<>(ObjectPropertyInputs.EMPTY);
    private final AtomicReference<NiagaraPropertyInterfaceInputs> propertyInterfaces = new AtomicReference<>(NiagaraPropertyInterfaceInputs.EMPTY);
    private final AtomicReference<ActorComponentInputs> actorComponentInputs = new AtomicReference<>(ActorComponentInputs.EMPTY);
    private final AtomicReference<NiagaraSocketInputs> socketInputs = new AtomicReference<>(NiagaraSocketInputs.EMPTY);
    private final AtomicReference<@Nullable Boolean> niagaraAutoManageAttachment = new AtomicReference<>();
    private final AtomicReference<@Nullable NiagaraAutoAttachmentParameters> niagaraAutoAttachmentParameters = new AtomicReference<>();
    private final AtomicReference<@Nullable Pose> niagaraAttachmentPose = new AtomicReference<>();
    private final AtomicReference<NiagaraSplineInputs> splineInputs = new AtomicReference<>(NiagaraSplineInputs.EMPTY);
    private final AtomicReference<Map<String, NiagaraIntDistribution>> intDistributions = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, String>> waterSources = new AtomicReference<>(Map.of());
    private final AtomicReference<NiagaraMovieRenderSample> movieRenderSample = new AtomicReference<>(NiagaraMovieRenderSample.INACTIVE);
    private volatile @Nullable Integer niagaraRandomSeedOffset;
    private volatile @Nullable Boolean niagaraRenderingEnabled;
    private volatile @Nullable Boolean niagaraAllowScalability;
    private volatile boolean niagaraLocalPlayerEffect;
    private volatile @Nullable OcclusionMode niagaraOcclusionMode;
    private final AtomicReference<@Nullable NiagaraCollisionWorld> collisionWorld = new AtomicReference<>();
    private final AtomicReference<NiagaraStaticMeshInputs> staticMeshInputs = new AtomicReference<>(NiagaraStaticMeshInputs.EMPTY);
    private final AtomicReference<NiagaraSkeletalMeshInputs> skeletalMeshInputs = new AtomicReference<>(NiagaraSkeletalMeshInputs.EMPTY);
    private final AtomicReference<NiagaraRigidMeshInputs> rigidMeshInputs = new AtomicReference<>(NiagaraRigidMeshInputs.EMPTY);
    private final AtomicReference<NiagaraGeometryCollectionInputs> geometryCollections = new AtomicReference<>(NiagaraGeometryCollectionInputs.EMPTY);
    private final AtomicReference<NiagaraGroomInputs> groomInputs = new AtomicReference<>(NiagaraGroomInputs.EMPTY);
    private final AtomicReference<NiagaraSceneCaptureInputs> sceneCaptureInputs = new AtomicReference<>(NiagaraSceneCaptureInputs.EMPTY);
    private final AtomicReference<NiagaraLandscapeInputs> landscapeInputs = new AtomicReference<>(NiagaraLandscapeInputs.EMPTY);
    private final AtomicReference<NiagaraVirtualTextureInputs> virtualTextureInputs = new AtomicReference<>(NiagaraVirtualTextureInputs.EMPTY);
    private final AtomicReference<Map<String, NiagaraParticleExport>> particleExports = new AtomicReference<>(Map.of());
    private final AtomicReference<@Nullable NiagaraBounds> niagaraSystemFixedBounds = new AtomicReference<>();
    private final AtomicReference<@Nullable NiagaraBounds> niagaraOwnerBounds = new AtomicReference<>();
    private final AtomicReference<List<String>> niagaraOwnerGameplayTags = new AtomicReference<>(List.of());
    private final AtomicReference<@Nullable String> niagaraOwnerComponentType = new AtomicReference<>();
    private final AtomicReference<List<String>> niagaraOwnerComponentSuperclasses = new AtomicReference<>(List.of());
    private final AtomicReference<NiagaraEmitterOverrides> niagaraEmitterOverrides =
            new AtomicReference<>(NiagaraEmitterOverrides.EMPTY);
    private final AtomicReference<@Nullable Playback> niagaraCache = new AtomicReference<>();
    private final AtomicReference<Map<String, NiagaraSimulationCache>> simCacheReaderInputs = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, SparseVolumeTextureAsset>> sparseVolumeInputs = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, Object>> materialCollectionInputs = new AtomicReference<>(Map.of());
    private final AtomicReference<@Nullable NiagaraCacheCapture> niagaraCacheCapture = new AtomicReference<>();
    private final AtomicReference<@Nullable NiagaraAudioBackend> niagaraAudioBackend = new AtomicReference<>();
    private final AtomicReference<Map<String, Overrides>> niagaraAudioOverrides = new AtomicReference<>(Map.of());
    private final AtomicReference<List<ObjectPropertyInputs.Remap>> objectReaderRemaps = new AtomicReference<>(List.of());
    private record ArrayResults(Map<String, ArrayParameter> values, Map<String, ArrayParameter> overrides) { }
    private final AtomicReference<ArrayResults> arrayResults = new AtomicReference<>(new ArrayResults(Map.of(), Map.of()));
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean beamEndpointSet = new AtomicBoolean();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicReference<List<String>> unitySubEmitterRequests = new AtomicReference<>(List.of());
    private volatile UnityCollisionLayers unityCollisionLayers = UnityCollisionLayers.EMPTY;
    private volatile Map<Identifier, StaticMeshGeometry> unityEmissionGeometry = Map.of();
    private volatile Map<String, FieldState> unityForceFields = Map.of();
    private volatile Map<String, List<com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider>> unityTriggerColliders = Map.of();
    private volatile java.util.function.@Nullable Consumer<UnityParticleTriggerEvent> unityTriggerListener;
    private volatile java.util.function.@Nullable Consumer<UnityParticleCollisionEvent> unityCollisionListener;
    private volatile @Nullable Identifier effectId;
    private volatile java.util.function.@Nullable Consumer<UnityParticleCollisionEvent.ForceImpact> unityColliderForceListener;

    public @Nullable Identifier effectId() { return effectId; }
    public void setEffectId(@Nullable Identifier effectId) { this.effectId = effectId; }
    private final AtomicBoolean niagaraPaused = new AtomicBoolean();
    private final AtomicReference<NiagaraComponentTickSettings> niagaraTickSettings =
            new AtomicReference<>(NiagaraComponentTickSettings.DEFAULT);
    private final AtomicReference<NiagaraAgeUpdate> niagaraAgeUpdate = new AtomicReference<>(NiagaraAgeUpdate.DEFAULT);
    private final AtomicReference<@Nullable Warmup> niagaraWarmup = new AtomicReference<>();

    private final @Nullable NiagaraComponentControl niagaraComponent;
    private record PrimitiveDataState(PrimitiveCustomData defaults, PrimitiveCustomData current) { }
    private final AtomicReference<PrimitiveDataState> customPrimitiveData;

    public EffectHandle(Vec3 position) {
        this(position, null);
    }

    public EffectHandle(Vec3 position, @Nullable NiagaraComponentControl niagaraComponent) {
        this(position, niagaraComponent, PrimitiveCustomData.EMPTY);
    }

    public EffectHandle(Vec3 position, @Nullable NiagaraComponentControl niagaraComponent, PrimitiveCustomData defaultCustomPrimitiveData) {
        this.niagaraComponent = niagaraComponent;
        var defaults = Objects.requireNonNull(defaultCustomPrimitiveData);
        customPrimitiveData = new AtomicReference<>(new PrimitiveDataState(defaults, defaults));
        input = new AtomicReference<>(new Input(Pose.at(position), position, 0, false, 1, 1, 1, 1, Map.of()));
    }

    public @Nullable NiagaraComponentControl niagaraComponent() { return niagaraComponent; }

    public void setPose(Pose pose) {
        input.updateAndGet(old -> old.withPose(pose, old.sequence() + 1,
                old.actorPositionExplicit() ? old.actorPosition() : pose.position(), old.actorPositionExplicit()));
    }

    public void setPoseAndActorPosition(Pose pose, Vec3 actorPosition) {
        if (pose == null || !finite(actorPosition)) throw new IllegalArgumentException("Invalid effect attachment");
        input.updateAndGet(old -> old.withPose(pose, old.sequence() + 1, actorPosition, true));
    }

    /** Publishes one attachment sample including actor velocity in source axes and centimetres per second. */
    public void setPoseAndActorPosition(Pose pose, Vec3 actorPosition, @Nullable Velocity velocity) {
        if (pose == null || !finite(actorPosition)) throw new IllegalArgumentException("Invalid effect attachment");
        input.updateAndGet(old -> old.withPose(pose, old.sequence() + 1, actorPosition, true, velocity));
    }

    public void moveTo(Vec3 position) {
        input.updateAndGet(old -> {
            var pose = old.pose();
            return old.withPose(new Pose(position, pose.x(), pose.y(), pose.z(), pose.w(),
                    pose.scale(), pose.scaleY(), pose.scaleZ()), old.sequence() + 1,
                    old.actorPositionExplicit() ? old.actorPosition() : position, old.actorPositionExplicit());
        });
    }

    public void setBeamEnd(Vec3 end) {
        if (!finite(end)) throw new IllegalArgumentException("Non-finite beam endpoint");
        input.updateAndGet(old -> new Input(old.pose(), end, old.sequence(), old.renderTracked(),
                old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), old.arrayParameters(),
                old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), old.receivesDecals(),
                old.textureParameters(), old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(),
                old.customPrimitiveData(), old.ownerVelocity()));
        beamEndpointSet.set(true);
    }

    /** The explicit world-space endpoint, or null when the effect should use its authored default. */
    public @Nullable Vec3 beamEndOverride() {
        return beamEndpointSet.get() ? input.get().beamEnd() : null;
    }

    public void setColor(float red, float green, float blue, float alpha) {
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue) || !Float.isFinite(alpha))
            throw new IllegalArgumentException("Non-finite effect color");
        input.updateAndGet(old -> old.withColor(red, green, blue, alpha));
    }

    /** Updates primitive reception without publishing a new pose sample or restarting emission. */
    public void setReceivesDecals(boolean receivesDecals) {
        input.updateAndGet(old -> old.receivesDecals() == receivesDecals ? old
                : new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                        old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), old.arrayParameters(),
                        old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), receivesDecals, old.textureParameters(), old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity()));
    }

    public void setFirstPerson(boolean firstPerson) {
        input.updateAndGet(old -> old.firstPerson() == firstPerson ? old
                : new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                        old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), old.arrayParameters(),
                        old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), old.receivesDecals(),
                        old.textureParameters(), firstPerson, old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity()));
    }

    /** Render-backed anchors suspend emission while their model is culled. Existing particles still finish. */
    public void trackRenderSamples() {
        input.updateAndGet(old -> old.renderTracked() ? old : new Input(old.pose(), old.beamEnd(), old.sequence(), true,
                old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), old.arrayParameters(),
                old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), old.receivesDecals(),
                old.textureParameters(), old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(),
                old.customPrimitiveData(), old.ownerVelocity()));
    }

    /** Publishes one scalar Niagara host parameter by its fully qualified variable name. */
    public void setParameter(String variableName, float value) {
        setParameter(variableName, ParameterValue.ofFloat(value));
    }

    public void setParameter(String variableName, ParameterValue value) {
        if (variableName == null || variableName.isBlank() || value == null)
            throw new IllegalArgumentException("Invalid effect parameter");
        input.updateAndGet(old -> {
            Map<String, ParameterValue> parameters = new HashMap<>(old.parameters());
            parameters.keySet().removeIf(name -> SourceNames.equal(name, variableName));
            parameters.put(variableName, value);
            return new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                    old.red(), old.green(), old.blue(), old.alpha(), parameters, old.arrayParameters(), old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), old.receivesDecals(), old.textureParameters(), old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity());
        });
    }

    public void removeParameter(String variableName) {
        input.updateAndGet(old -> {
            if (old.parameters().keySet().stream().noneMatch(name -> SourceNames.equal(name, variableName))) return old;
            Map<String, ParameterValue> parameters = new HashMap<>(old.parameters());
            parameters.keySet().removeIf(name -> SourceNames.equal(name, variableName));
            return new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                    old.red(), old.green(), old.blue(), old.alpha(), parameters, old.arrayParameters(), old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), old.receivesDecals(), old.textureParameters(), old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity());
        });
    }

    public void setArrayParameter(String variableName, ArrayParameter value) {
        if (variableName == null || variableName.isBlank() || value == null)
            throw new IllegalArgumentException("Invalid Niagara array parameter");
        ArrayParameter assigned = new ArrayParameter(value.type(), value.words);
        input.updateAndGet(old -> {
            Map<String, ArrayParameter> parameters = new LinkedHashMap<>(old.arrayParameters());
            parameters.keySet().removeIf(name -> ArrayParameter.matchesName(name, variableName));
            parameters.put(variableName, assigned);
            return new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                    old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), parameters, old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), old.receivesDecals(), old.textureParameters(), old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity());
        });
    }

    public void removeArrayParameter(String variableName) {
        if (variableName == null || variableName.isBlank())
            throw new IllegalArgumentException("Invalid Niagara array parameter name");
        input.updateAndGet(old -> {
            if (ArrayParameter.find(old.arrayParameters(), variableName) == null) return old;
            Map<String, ArrayParameter> parameters = new LinkedHashMap<>(old.arrayParameters());
            parameters.keySet().removeIf(name -> ArrayParameter.matchesName(name, variableName));
            return new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                    old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), parameters, old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), old.receivesDecals(), old.textureParameters(), old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity());
        });
    }

    public void setRendererParameter(String variableName, RendererParameter value) {
        if (variableName == null || variableName.isBlank() || value == null)
            throw new IllegalArgumentException("Invalid Niagara renderer object parameter");
        input.updateAndGet(old -> {
            var parameters = new LinkedHashMap<>(old.rendererParameters());
            parameters.keySet().removeIf(name -> SourceNames.equal(name, variableName));
            parameters.put(variableName, value);
            return new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                    old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), old.arrayParameters(),
                    old.actorPosition(), old.actorPositionExplicit(), parameters, old.receivesDecals(), old.textureParameters(), old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity());
        });
    }

    public void removeRendererParameter(String variableName) {
        if (variableName == null || variableName.isBlank())
            throw new IllegalArgumentException("Invalid Niagara renderer object parameter name");
        input.updateAndGet(old -> {
            if (RendererParameter.named(old.rendererParameters(), variableName) == null) return old;
            var parameters = new LinkedHashMap<>(old.rendererParameters());
            parameters.keySet().removeIf(name -> SourceNames.equal(name, variableName));
            return new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                    old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), old.arrayParameters(),
                    old.actorPosition(), old.actorPositionExplicit(), parameters, old.receivesDecals(), old.textureParameters(), old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity());
        });
    }

    public void setTextureParameter(String variableName, TextureParameter value) {
        if (variableName == null || variableName.isBlank() || value == null)
            throw new IllegalArgumentException("Invalid Niagara texture parameter");
        input.updateAndGet(old -> {
            Map<String, TextureParameter> textures = new HashMap<>(old.textureParameters());
            textures.keySet().removeIf(name -> SourceNames.equal(name, variableName));
            textures.put(variableName, value);
            return new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                    old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), old.arrayParameters(),
                    old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), old.receivesDecals(), textures, old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity());
        });
    }

    public void removeTextureParameter(String variableName) {
        if (variableName == null || variableName.isBlank()) throw new IllegalArgumentException("Invalid Niagara texture parameter name");
        input.updateAndGet(old -> {
            if (old.textureParameters().keySet().stream().noneMatch(name -> SourceNames.equal(name, variableName))) return old;
            var textures = new HashMap<>(old.textureParameters());
            textures.keySet().removeIf(name -> SourceNames.equal(name, variableName));
            return new Input(old.pose(), old.beamEnd(), old.sequence(), old.renderTracked(),
                    old.red(), old.green(), old.blue(), old.alpha(), old.parameters(), old.arrayParameters(),
                    old.actorPosition(), old.actorPositionExplicit(), old.rendererParameters(), old.receivesDecals(), textures, old.firstPerson(), old.primitiveLighting(), old.customDepthStencil(), old.customPrimitiveData(), old.ownerVelocity());
        });
    }

    public ArrayParameter arrayParameter(String variableName) {
        ArrayResults result = arrayResults.get();
        ArrayParameter completed = ArrayParameter.find(result.values(), variableName);
        if (completed == null) return ArrayParameter.find(input.get().arrayParameters(), variableName);
        List<String> names = new ArrayList<>();
        for (var entry : result.values().entrySet()) if (entry.getValue() == completed) names.add(entry.getKey());
        ArrayParameter current = ArrayParameter.latest(input.get().arrayParameters(), names);
        return current != ArrayParameter.latest(result.overrides(), names) ? current : completed;
    }

    /** Publishes the most recent completed Niagara data-interface state. */
    public void publishArrayResults(Map<String, ArrayParameter> values, Map<String, ArrayParameter> overrides) {
        arrayResults.set(new ArrayResults(Map.copyOf(values), ArrayParameter.snapshot(overrides)));
    }

    public Input input() {
        Input requested = input.get();
        Pose attached = niagaraAttachmentPose.get();
        return attached == null ? requested : requested.withPose(attached);
    }
    /** Caller-published pose before Niagara's optional component attachment is applied. */
    public Input inputWithoutNiagaraAttachment() { return input.get(); }
    public void setNiagaraAttachmentPose(@Nullable Pose pose) {
        niagaraAttachmentPose.set(pose);
    }
    /** Native CancelAutoAttachment restores the saved relative transform before detaching. */
    public void restoreNiagaraDetachedPose(Pose pose) {
        Objects.requireNonNull(pose);
        niagaraAttachmentPose.set(null);
        input.updateAndGet(old -> old.withPose(pose));
    }
    public @Nullable Boolean niagaraAutoManageAttachment() { return niagaraAutoManageAttachment.get(); }
    public void setNiagaraAutoManageAttachment(@Nullable Boolean enabled) {
        niagaraAutoManageAttachment.set(enabled);
    }
    public @Nullable NiagaraAutoAttachmentParameters niagaraAutoAttachmentParameters() {
        return niagaraAutoAttachmentParameters.get();
    }
    public void setNiagaraAutoAttachmentParameters(@Nullable NiagaraAutoAttachmentParameters parameters) {
        niagaraAutoAttachmentParameters.set(parameters);
    }

    /** Supplies the host component's effective lighting settings. Null restores the authored values. */
    public void setPrimitiveLighting(@Nullable PrimitiveLighting lighting) { primitiveLightingOverride = lighting; }
    public @Nullable PrimitiveLighting primitiveLightingOverride() { return primitiveLightingOverride; }

    public @Nullable PrimitiveOwnerVisibility niagaraOwnerVisibility() {
        return niagaraOwnerVisibility;
    }

    /** Overrides the source primitive's owner visibility. A null value uses the asset defaults. */
    public void setNiagaraOwnerVisibility(@Nullable PrimitiveOwnerVisibility visibility) {
        niagaraOwnerVisibility = visibility;
    }

    /** Changes the component's translucent draw order. Null restores the saved component settings. */
    public void setNiagaraTranslucencySort(@Nullable NiagaraTranslucencySort sort) { niagaraTranslucencySortOverride = sort; }
    public @Nullable NiagaraTranslucencySort niagaraTranslucencySortOverride() { return niagaraTranslucencySortOverride; }

    public Set<UUID> niagaraViewOwners() { return niagaraViewOwners; }

    /** Publishes the host actor and its owner chain as view-actor identities. */
    public void setNiagaraViewOwners(Set<UUID> owners) { niagaraViewOwners = Set.copyOf(owners); }
    /** Null restores the system's authored CustomDepth and stencil settings. */
    public void setCustomDepthStencil(@Nullable CustomDepthStencil depth) { customDepthStencilOverride = depth; }
    public @Nullable CustomDepthStencil customDepthStencilOverride() { return customDepthStencilOverride; }

    /** Writes consecutive source material primitive slots, truncated at the end of the component buffer. */
    public void setCustomPrimitiveData(int first, float... values) {
        customPrimitiveData.updateAndGet(state -> {
            var current = state.current().with(first, values);
            return current == state.current() ? state : new PrimitiveDataState(state.defaults(), current);
        });
    }

    /** Updates the component defaults and restores them as the current data when any default changes. */
    public void setDefaultCustomPrimitiveData(int first, float... values) {
        customPrimitiveData.updateAndGet(state -> {
            var defaults = state.defaults().with(first, values);
            return defaults == state.defaults() ? state : new PrimitiveDataState(defaults, defaults);
        });
    }

    public void resetCustomPrimitiveData() {
        customPrimitiveData.updateAndGet(state -> state.current() == state.defaults() ? state
                : new PrimitiveDataState(state.defaults(), state.defaults()));
    }

    public PrimitiveCustomData customPrimitiveData() { return customPrimitiveData.get().current(); }
    public Map<Key, Reference> dataInterfaceParameters() { return dataInterfaceParameters.get(); }
    public ObjectPropertyInputs objectPropertyInputs() { return propertyInputs.get(); }
    public NiagaraPropertyInterfaceInputs niagaraPropertyInterfaceInputs() { return propertyInterfaces.get(); }
    public void setNiagaraPropertyInterfaceInputs(NiagaraPropertyInterfaceInputs inputs) {
        propertyInterfaces.set(Objects.requireNonNull(inputs));
    }
    public ActorComponentInputs actorComponentInputs() { return actorComponentInputs.get(); }
    public void setActorComponentInputs(ActorComponentInputs inputs) { actorComponentInputs.set(Objects.requireNonNull(inputs)); }
    public NiagaraSocketInputs niagaraSocketInputs() { return socketInputs.get(); }
    public void setNiagaraSocketInputs(NiagaraSocketInputs inputs) { socketInputs.set(Objects.requireNonNull(inputs)); }
    public NiagaraSplineInputs niagaraSplineInputs() { return splineInputs.get(); }
    public void setNiagaraSplineInputs(NiagaraSplineInputs inputs) { splineInputs.set(Objects.requireNonNull(inputs)); }

    public void setNiagaraIntDistribution(String variable, List<NiagaraWeightedInt> entries) {
        if (variable.isBlank()) throw new IllegalArgumentException("Missing Niagara distribution parameter name");
        String name = SourceNames.canonical(variable);
        var distribution = NiagaraIntDistribution.build(List.copyOf(entries));
        intDistributions.updateAndGet(previous -> {
            var updated = new HashMap<>(previous);
            updated.put(name, distribution);
            return Map.copyOf(updated);
        });
    }

    public void removeNiagaraIntDistribution(String variable) {
        String name = SourceNames.canonical(variable);
        intDistributions.updateAndGet(previous -> {
            if (!previous.containsKey(name)) return previous;
            var updated = new HashMap<>(previous);
            updated.remove(name);
            return Map.copyOf(updated);
        });
    }

    /** Set the actor/component path for a typed Water DI variable. Empty selects no body, null restores its authored source. */
    public void setNiagaraWaterSource(String variable, @Nullable String source) {
        String name = SourceNames.canonical(variable);
        if (name.isBlank()) throw new IllegalArgumentException("Missing Niagara Water interface variable");
        waterSources.updateAndGet(previous -> {
            var next = new HashMap<>(previous);
            if (source == null) next.remove(name);
            else next.put(name, source);
            return Map.copyOf(next);
        });
    }

    public Map<String, String> niagaraWaterSources() { return waterSources.get(); }

    public Map<String, NiagaraIntDistribution> niagaraIntDistributions() { return intDistributions.get(); }
    public NiagaraMovieRenderSample niagaraMovieRenderSample() { return movieRenderSample.get(); }
    public void setNiagaraMovieRenderSample(NiagaraMovieRenderSample sample) { movieRenderSample.set(Objects.requireNonNull(sample)); }
    public @Nullable Integer niagaraRandomSeedOffset() { return niagaraRandomSeedOffset; }
    public void setNiagaraRandomSeedOffset(int offset) { niagaraRandomSeedOffset = offset; }

    /** Changes proxy visibility without suspending Niagara simulation or its component renderers. */
    public void setNiagaraRenderingEnabled(boolean enabled) { niagaraRenderingEnabled = enabled; }
    public void setNiagaraRenderingEnabled(@Nullable Boolean enabled) { niagaraRenderingEnabled = enabled; }
    public @Nullable Boolean niagaraRenderingEnabled() { return niagaraRenderingEnabled; }

    public void setNiagaraAllowScalability(boolean enabled) { niagaraAllowScalability = enabled; }
    public void setNiagaraAllowScalability(@Nullable Boolean enabled) { niagaraAllowScalability = enabled; }
    public @Nullable Boolean niagaraAllowScalability() { return niagaraAllowScalability; }

    /** Publishes whether the host owner, attachment or instigator is the local player. */
    public void setNiagaraLocalPlayerEffect(boolean localPlayer) { niagaraLocalPlayerEffect = localPlayer; }
    public boolean niagaraLocalPlayerEffect() { return niagaraLocalPlayerEffect; }

    public void setNiagaraOcclusionMode(@Nullable OcclusionMode mode) {
        niagaraOcclusionMode = mode;
    }
    public @Nullable OcclusionMode niagaraOcclusionMode() {
        return niagaraOcclusionMode;
    }
    public @Nullable NiagaraCollisionWorld niagaraCollisionWorld() { return collisionWorld.get(); }
    /** Sets the collision scene for subsequent simulation steps. Null selects the client world adapter. */
    public void setNiagaraCollisionWorld(@Nullable NiagaraCollisionWorld world) { collisionWorld.set(world); }
    public NiagaraStaticMeshInputs niagaraStaticMeshInputs() { return staticMeshInputs.get(); }
    public void setNiagaraStaticMeshInputs(NiagaraStaticMeshInputs inputs) { staticMeshInputs.set(Objects.requireNonNull(inputs)); }
    public NiagaraSkeletalMeshInputs niagaraSkeletalMeshInputs() { return skeletalMeshInputs.get(); }
    public void setNiagaraSkeletalMeshInputs(NiagaraSkeletalMeshInputs inputs) { skeletalMeshInputs.set(Objects.requireNonNull(inputs)); }
    public NiagaraRigidMeshInputs niagaraRigidMeshInputs() { return rigidMeshInputs.get(); }
    public void setNiagaraRigidMeshInputs(NiagaraRigidMeshInputs inputs) { rigidMeshInputs.set(Objects.requireNonNull(inputs)); }
    public NiagaraGeometryCollectionInputs niagaraGeometryCollectionInputs() { return geometryCollections.get(); }
    public void setNiagaraGeometryCollectionInputs(NiagaraGeometryCollectionInputs inputs) { geometryCollections.set(Objects.requireNonNull(inputs)); }
    public NiagaraGroomInputs niagaraGroomInputs() { return groomInputs.get(); }
    public void setNiagaraGroomInputs(NiagaraGroomInputs inputs) { groomInputs.set(Objects.requireNonNull(inputs)); }
    public NiagaraSceneCaptureInputs niagaraSceneCaptureInputs() { return sceneCaptureInputs.get(); }
    public void setNiagaraSceneCaptureInputs(NiagaraSceneCaptureInputs inputs) { sceneCaptureInputs.set(Objects.requireNonNull(inputs)); }
    public NiagaraLandscapeInputs niagaraLandscapeInputs() { return landscapeInputs.get(); }
    public void setNiagaraLandscapeInputs(NiagaraLandscapeInputs inputs) { landscapeInputs.set(Objects.requireNonNull(inputs)); }
    public NiagaraVirtualTextureInputs niagaraVirtualTextureInputs() { return virtualTextureInputs.get(); }
    public void setNiagaraVirtualTextureInputs(NiagaraVirtualTextureInputs inputs) { virtualTextureInputs.set(Objects.requireNonNull(inputs)); }
    public Map<String, NiagaraParticleExport> niagaraParticleExports() { return particleExports.get(); }
    public @Nullable NiagaraBounds niagaraSystemFixedBounds() { return niagaraSystemFixedBounds.get(); }
    public void setNiagaraSystemFixedBounds(@Nullable NiagaraBounds bounds) { niagaraSystemFixedBounds.set(bounds); }
    public @Nullable NiagaraBounds niagaraOwnerBounds() { return niagaraOwnerBounds.get(); }
    public List<String> niagaraOwnerGameplayTags() { return niagaraOwnerGameplayTags.get(); }
    public @Nullable String niagaraOwnerComponentType() { return niagaraOwnerComponentType.get(); }
    public List<String> niagaraOwnerComponentSuperclasses() { return niagaraOwnerComponentSuperclasses.get(); }
    public void setNiagaraOwnerMetadata(@Nullable NiagaraBounds bounds, List<String> gameplayTags,
                                        @Nullable String componentType) {
        setNiagaraOwnerMetadata(bounds, gameplayTags, componentType, List.of());
    }
    public void setNiagaraOwnerMetadata(@Nullable NiagaraBounds bounds, List<String> gameplayTags,
                                        @Nullable String componentType, List<String> componentSuperclasses) {
        niagaraOwnerBounds.set(bounds);
        niagaraOwnerGameplayTags.set(gameplayTags == null ? List.of() : List.copyOf(gameplayTags));
        niagaraOwnerComponentType.set(componentType);
        niagaraOwnerComponentSuperclasses.set(componentSuperclasses == null ? List.of() : List.copyOf(componentSuperclasses));
    }

    /** Changes whether the named emitter can spawn. Existing particles continue their simulation. */
    public void setNiagaraEmitterEnabled(String emitter, boolean enabled) {
        niagaraEmitterOverrides.updateAndGet(values -> values.withSpawning(emitter, enabled));
    }

    /** Sets the named emitter's fixed local bounds in source centimetres. Null clears the override. */
    public void setNiagaraEmitterFixedBounds(String emitter, @Nullable NiagaraBounds bounds) {
        niagaraEmitterOverrides.updateAndGet(values -> values.withFixedBounds(emitter, bounds));
    }

    public NiagaraEmitterOverrides niagaraEmitterOverrides() { return niagaraEmitterOverrides.get(); }

    /** Supplies the owner actor's velocity in source axes and centimetres per second. */
    public void setNiagaraOwnerVelocity(@Nullable Velocity velocity) {
        input.updateAndGet(old -> Objects.equals(old.ownerVelocity(), velocity) ? old
                : old.withPose(old.pose(), old.sequence(), old.actorPosition(), old.actorPositionExplicit(), velocity));
    }

    public @Nullable Velocity niagaraOwnerVelocity() {
        return niagaraOwnerVelocity(input.get());
    }

    public @Nullable Velocity niagaraOwnerVelocity(Input sample) {
        var published = sample.ownerVelocity();
        if (published != null) return published;
        var attached = actorComponentInputs().attached();
        return attached == null ? null : attached.ownerVelocity();
    }
    public @Nullable Playback niagaraCache() { return niagaraCache.get(); }

    /** Plays a saved simulation from its start, or evaluates the cache at an explicit source age in seconds. */
    public void setNiagaraCache(@Nullable NiagaraSimulationCache cache, @Nullable Float age) {
        if (cache == null && age != null) throw new IllegalArgumentException("A playback age requires a Niagara cache");
        niagaraCache.set(cache == null ? null : new Playback(cache, age));
    }
    public Map<String, NiagaraSimulationCache> niagaraSimCacheReaderInputs() { return simCacheReaderInputs.get(); }
    public Map<String, SparseVolumeTextureAsset> niagaraSparseVolumeInputs() { return sparseVolumeInputs.get(); }
    public Map<String, ?> niagaraMaterialCollectionInputs() { return materialCollectionInputs.get(); }

    public void setNiagaraMaterialCollection(String parameter, @Nullable MaterialCollectionDefinition collection) {
        setMaterialCollectionInput(parameter, collection);
    }

    public void setNiagaraMaterialCollectionInstance(String parameter, @Nullable MaterialCollectionInstance instance) {
        setMaterialCollectionInput(parameter, instance);
    }

    private void setMaterialCollectionInput(String parameter, @Nullable Object value) {
        String name = SourceNames.canonical(UserParameterNames.display(Objects.requireNonNull(parameter)));
        if (name.isBlank() || name.equals("none")) throw new IllegalArgumentException("A material collection input requires an object parameter name");
        materialCollectionInputs.updateAndGet(previous -> {
            var next = new HashMap<>(previous);
            if (value == null) next.remove(name);
            else next.put(name, value);
            return Map.copyOf(next);
        });
    }

    public void setNiagaraSparseVolumeInput(String parameter, @Nullable SparseVolumeTextureAsset texture) {
        String name = SourceNames.canonical(UserParameterNames.display(Objects.requireNonNull(parameter)));
        if (name.isBlank() || name.equals("none")) throw new IllegalArgumentException("A sparse volume input requires an object parameter name");
        sparseVolumeInputs.updateAndGet(previous -> {
            var next = new HashMap<>(previous);
            if (texture == null) next.remove(name);
            else next.put(name, texture);
            return Map.copyOf(next);
        });
    }

    public NiagaraCacheCapture captureNiagara(NiagaraCacheCapture.Options options) {
        return startNiagaraCapture(options, null, false, 0);
    }

    /** Manually advances the effect and records the selected steps on the owning render thread. */
    public NiagaraCacheCapture captureNiagaraImmediately(NiagaraCacheCapture.Options options, float stepSeconds) {
        return startNiagaraCapture(options, null, false, Math.max(checkedCacheDelta(stepSeconds), .0001f));
    }

    /** Captures the current completed simulation state on its owning thread. */
    public NiagaraCacheCapture captureCurrentNiagara(NiagaraCacheCapture.Options options) {
        return startNiagaraCapture(options, null, true, 0);
    }

    /** Advances the source simulation once at the specified delta and captures that completed step. */
    public NiagaraCacheCapture captureCurrentNiagara(NiagaraCacheCapture.Options options, float advanceSeconds) {
        float delta = checkedCacheDelta(advanceSeconds);
        return startNiagaraCapture(options, null, true, delta > 1e-8f ? delta : 0f);
    }

    /** Appends live frames to a new cache using the recorded system layout. */
    public NiagaraCacheCapture appendNiagaraCache(NiagaraSimulationCache source, NiagaraCacheCapture.Options options) {
        return startNiagaraCapture(options, Objects.requireNonNull(source), false, 0);
    }

    /** Appends selected manual steps using the recorded system layout. */
    public NiagaraCacheCapture appendNiagaraCacheImmediately(NiagaraSimulationCache source, NiagaraCacheCapture.Options options,
                                                             float stepSeconds) {
        return startNiagaraCapture(options, Objects.requireNonNull(source), false, Math.max(checkedCacheDelta(stepSeconds), .0001f));
    }

    private static float checkedCacheDelta(float seconds) {
        if (!Float.isFinite(seconds) || seconds <= 0)
            throw new IllegalArgumentException("Niagara capture advance requires a positive finite delta");
        return seconds;
    }

    private NiagaraCacheCapture startNiagaraCapture(NiagaraCacheCapture.Options options, @Nullable NiagaraSimulationCache source,
                                                    boolean currentFrame, float advanceDelta) {
        if (!alive.get()) throw new IllegalStateException("Cannot capture a closed effect");
        var capture = new NiagaraCacheCapture(Objects.requireNonNull(options), source, currentFrame, advanceDelta);
        if (!niagaraCacheCapture.compareAndSet(null, capture))
            throw new IllegalStateException("This effect already has a Niagara capture");
        capture.result().whenComplete((result, failure) -> niagaraCacheCapture.compareAndSet(capture, null));
        if (!alive.get()) capture.result().completeExceptionally(new IllegalStateException("Effect closed before Niagara capture"));
        return capture;
    }

    public @Nullable NiagaraCacheCapture niagaraCacheCapture() { return niagaraCacheCapture.get(); }

    /** Supplies the user object selected by a Niagara SimCache Reader. */
    public void setNiagaraSimCacheReaderInput(String parameter, @Nullable NiagaraSimulationCache cache) {
        String name = SourceNames.canonical(UserParameterNames.display(Objects.requireNonNull(parameter)));
        if (name.isBlank() || name.equals("none")) throw new IllegalArgumentException("A Niagara cache reader requires an object parameter name");
        simCacheReaderInputs.updateAndGet(previous -> {
            var next = new HashMap<>(previous);
            if (cache == null) next.remove(name);
            else next.put(name, cache);
            return Map.copyOf(next);
        });
    }
    public @Nullable NiagaraAudioBackend niagaraAudioBackend() { return niagaraAudioBackend.get(); }
    public void setNiagaraAudioBackend(@Nullable NiagaraAudioBackend backend) { niagaraAudioBackend.set(backend); }
    public Map<String, Overrides> niagaraAudioOverrides() { return niagaraAudioOverrides.get(); }
    public void setNiagaraAudioOverrides(String parameter, @Nullable Overrides value) {
        String key = SourceNames.canonical(UserParameterNames.display(Objects.requireNonNull(parameter)));
        if (key.isBlank() || key.equals("none")) throw new IllegalArgumentException("A Niagara audio override requires a user parameter name");
        niagaraAudioOverrides.updateAndGet(previous -> {
            var next = new HashMap<>(previous);
            if (value == null) next.remove(key);
            else next.put(key, value);
            return Map.copyOf(next);
        });
    }
    public void setNiagaraParticleExport(String parameter, @Nullable NiagaraParticleExport callback) {
        String key = SourceNames.canonical(UserParameterNames.display(Objects.requireNonNull(parameter)));
        if (key.isBlank() || key.equals("none")) throw new IllegalArgumentException("A Niagara export callback requires a user parameter name");
        particleExports.updateAndGet(previous -> {
            var next = new HashMap<>(previous);
            if (callback == null) next.remove(key);
            else next.put(key, callback);
            return Map.copyOf(next);
        });
    }
    public List<ObjectPropertyInputs.Remap> objectReaderRemaps() { return objectReaderRemaps.get(); }

    public void setObjectPropertyInputs(ObjectPropertyInputs inputs) { propertyInputs.set(Objects.requireNonNull(inputs)); }

    /** Changes one reader's property path at the next simulation boundary. */
    public void setObjectReaderPropertyRemap(Key reader, String graphName, String propertyName) {
        var remap = new ObjectPropertyInputs.Remap(reader, graphName, propertyName);
        objectReaderRemaps.updateAndGet(previous -> {
            var next = new ArrayList<>(previous);
            next.removeIf(entry -> sameInterfaceParameter(entry.reader(), reader)
                    && SourceNames.equal(entry.graphName(), graphName));
            next.add(remap);
            return List.copyOf(next);
        });
    }

    /** Replaces an exposed DI slot with a prepared object of its declared class; null remains a bound null. */
    public void setDataInterfaceParameter(Key key, @Nullable ObjectValue value) {
        requireInterfaceKey(key);
        Reference assigned = new Reference(key, value);
        dataInterfaceParameters.updateAndGet(old -> {
            var values = new LinkedHashMap<>(old);
            values.keySet().removeIf(existing -> sameInterfaceParameter(existing, key));
            values.put(key, assigned);
            return Collections.unmodifiableMap(values);
        });
    }

    public void removeDataInterfaceParameter(Key key) {
        requireInterfaceKey(key);
        dataInterfaceParameters.updateAndGet(old -> {
            var values = new LinkedHashMap<>(old);
            if (!values.keySet().removeIf(existing -> sameInterfaceParameter(existing, key))) return old;
            return Collections.unmodifiableMap(values);
        });
    }

    /** Publishes a prepared UObject reference; null remains an explicit bound null. */
    public void setObjectParameter(Key key, @Nullable ObjectValue value) {
        requireObjectKey(key);
        Reference assigned = new Reference(key, value);
        objectParameters.updateAndGet(old -> {
            var values = new LinkedHashMap<>(old);
            values.keySet().removeIf(existing -> sameInterfaceParameter(existing, key));
            values.put(key, assigned);
            return Collections.unmodifiableMap(values);
        });
    }

    public void removeObjectParameter(Key key) {
        requireObjectKey(key);
        objectParameters.updateAndGet(old -> {
            var values = new LinkedHashMap<>(old);
            if (!values.keySet().removeIf(existing -> sameInterfaceParameter(existing, key))) return old;
            return Collections.unmodifiableMap(values);
        });
    }

    public Map<Key, Reference> objectParameters() { return objectParameters.get(); }

    private static void requireObjectKey(Key key) {
        if (key == null || key.storage() != Storage.OBJECT)
            throw new IllegalArgumentException("Component object input requires a typed UObject key");
    }

    private static void requireInterfaceKey(Key key) {
        if (key == null || key.storage() != Storage.INTERFACE)
            throw new IllegalArgumentException("Component data interface input requires a typed DI key");
    }
    private static boolean sameInterfaceParameter(Key left, Key right) {
        return UserParameterNames.equal(left.name(), right.name()) && SourceNames.equal(left.storeTypeName(), right.storeTypeName());
    }
    public ParameterCollectionValues collectionOverrides() { return collectionOverrides.get(); }
    public void setCollectionParameter(String collection, String name, String sourceType, ParameterValue value) {
        collectionOverrides.updateAndGet(old -> old.with(collection, name, sourceType, value));
    }
    public void setCollectionTextureParameter(String collection, String name, String sourceType, @Nullable TextureParameter value) {
        collectionOverrides.updateAndGet(old -> old.withTexture(collection, name, sourceType, value));
    }
    /** Overrides a prepared UObject reference in this component's collection instance. */
    public void setCollectionObjectParameter(String collection, String name, String sourceType, @Nullable ObjectValue value) {
        collectionOverrides.updateAndGet(old -> old.withObject(collection, name, sourceType, value));
    }
    public void removeCollectionParameter(String collection, String name, String sourceType) {
        collectionOverrides.updateAndGet(old -> old.withoutParameter(collection, name, sourceType));
    }
    public void clearCollectionOverride(String collection) {
        collectionOverrides.updateAndGet(old -> old.withoutCollection(collection));
    }
    /** Suspends this Niagara instance at the next simulation boundary. Closing the handle resumes its shutdown. */
    public void setNiagaraPaused(boolean paused) { niagaraPaused.set(paused); }
    public boolean niagaraPaused() { return niagaraPaused.get() && !stopping(); }

    /** Selects the component's solo SystemSimulation for subsequent Niagara ticks. */
    public void setNiagaraForceSolo(boolean enabled) {
        niagaraTickSettings.updateAndGet(settings -> settings.withForceSolo(enabled));
    }

    /** Scales this component's live delta-time tick. Niagara cache playback uses the unscaled host delta. */
    public void setNiagaraCustomTimeDilation(float dilation) {
        niagaraTickSettings.updateAndGet(settings -> settings.withCustomTimeDilation(dilation));
    }

    public NiagaraComponentTickSettings niagaraTickSettings() { return niagaraTickSettings.get(); }

    public void setNiagaraTickBehavior(@Nullable NiagaraTickBehavior behavior, @Nullable ComponentTickGroup group) {
        niagaraTickSettings.updateAndGet(settings -> settings.withTickBehavior(behavior, group));
    }

    /** Publishes the desired-age settings for a solo Niagara instance or its simulation cache. */
    public void setNiagaraAgeUpdate(NiagaraAgeUpdate update) { niagaraAgeUpdate.set(Objects.requireNonNull(update)); }

    public NiagaraAgeUpdate niagaraAgeUpdate() { return niagaraAgeUpdate.get(); }

    /** Overrides the system's warmup for the next Niagara instance initialization. Null uses the system asset. */
    public void setNiagaraWarmup(@Nullable Warmup warmup) { niagaraWarmup.set(warmup); }

    public @Nullable Warmup niagaraWarmup() { return niagaraWarmup.get(); }
    /** Requests the authored Manual links of this Unity emitter for its live particles on the next simulation step. */
    public void triggerUnitySubEmitters(String emitter) {
        if (emitter.isBlank()) throw new IllegalArgumentException("Unity emitter name is empty");
        if (!isAlive() || stopping()) throw new IllegalStateException("Effect is no longer accepting particle events");
        unitySubEmitterRequests.updateAndGet(requests -> {
            var next = new java.util.ArrayList<>(requests);
            next.add(emitter);
            return List.copyOf(next);
        });
    }

    public List<String> consumeUnitySubEmitterRequests() { return unitySubEmitterRequests.getAndSet(List.of()); }

    public void setUnityTriggerListener(java.util.function.@Nullable Consumer<UnityParticleTriggerEvent> listener) {
        unityTriggerListener = listener;
    }

    public void setUnityCollisionLayers(UnityCollisionLayers layers) { unityCollisionLayers = Objects.requireNonNull(layers); }
    public UnityCollisionLayers unityCollisionLayers() { return unityCollisionLayers; }

    /** Publishes deformed geometry indexed by the exported emitter shape's asset ID for the next simulation frame.
     * Geometry uses LFMesh coordinates in the particle shape's local space, with the original UVs and section IDs.
     * Missing keys use the saved author pose. Each effect handle owns its own publication.
     */
    public void setUnityEmissionGeometry(Map<Identifier, StaticMeshGeometry> shapes) {
        unityEmissionGeometry = Map.copyOf(shapes);
    }

    public Map<Identifier, StaticMeshGeometry> unityEmissionGeometry() { return unityEmissionGeometry; }

    /**
     * Publishes Unity force-field states by the exported {@code external_forces.fields[].source_id}.
     * A basis is measured in block units relative to the effect root. A state with no basis uses the
     * saved transform. Fields omitted from the snapshot use their saved transform and enabled state.
     */
    public void setUnityForceFields(Map<String, FieldState> fields) {
        unityForceFields = com.kltyton.lumenfx.effect.unity.UnityExternalForces.copyFieldStates(fields);
    }

    public Map<String, FieldState> unityForceFields() { return unityForceFields; }

    /** Publishes dynamic host-bound trigger colliders (in world space) keyed by hostKey or trigger slot name. */
    public void setUnityTriggerColliders(Map<String, List<com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider>> colliders) {
        var copy = new java.util.LinkedHashMap<String, List<com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider>>(colliders.size());
        colliders.forEach((key, list) -> copy.put(Objects.requireNonNull(key), List.copyOf(list)));
        unityTriggerColliders = Map.copyOf(copy);
    }

    public void setUnityTriggerCollider(String hostKey, com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider collider) {
        var copy = new java.util.LinkedHashMap<>(unityTriggerColliders);
        copy.put(Objects.requireNonNull(hostKey), List.of(Objects.requireNonNull(collider)));
        unityTriggerColliders = Map.copyOf(copy);
    }

    public Map<String, List<com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider>> unityTriggerColliders() {
        return unityTriggerColliders;
    }

    /** Receives each authored collision message after the simulation frame, on the client thread. */
    public void setUnityCollisionListener(java.util.function.@Nullable Consumer<UnityParticleCollisionEvent> listener) {
        unityCollisionListener = listener;
    }

    public void publishUnityCollisionEvents(List<UnityParticleCollisionEvent> events) {
        var listener = unityCollisionListener;
        if (listener != null) for (var event : events) listener.accept(event);
    }

    /** Receives authored Unity colliderForce impulses computed on entity collisions. */
    public void setUnityColliderForceListener(
            java.util.function.@Nullable Consumer<UnityParticleCollisionEvent.ForceImpact> listener) {
        unityColliderForceListener = listener;
    }

    public void publishUnityColliderForces(List<UnityParticleCollisionEvent.ForceImpact> forces) {
        var listener = unityColliderForceListener;
        if (listener != null) for (var force : forces) listener.accept(force);
    }

    public void publishUnityTriggerEvents(List<UnityParticleTriggerEvent> events) {
        var listener = unityTriggerListener;
        if (listener != null) for (var event : events) listener.accept(event);
    }

    public boolean stopping() { return stopping.get(); }
    public boolean isAlive() { return alive.get(); }
    public void invalidate() { alive.set(false); stopping.set(true); }
    @Override public void close() { stopping.set(true); }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
