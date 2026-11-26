package com.kltyton.lumenfx.internal.photon.core.registry;

import com.kltyton.lumenfx.internal.photon.core.LDLib2;
import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import net.minecraft.resources.Identifier;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AutoRegistry<A extends Annotation, C, V> extends LDLRegistry.String<AutoRegistry.Holder<A, C, V>> {
    /** Deterministic list of built-in Photon @LDLRegisterClient implementations (no classpath scanning needed). */
    private static final Map<java.lang.String, List<java.lang.String>> BUILTIN_CLASSES = Map.of(
            "photon:material", List.of(
                    "com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial$MissingMaterial",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.material.BlockTextureSheetMaterial",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.material.ResourceMaterial",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.material.SpriteMaterial",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.material.kila.KilaMaterial"
            ),
            "photon:model_source", List.of(
                    "com.lowdragmc.photon.client.gameobject.emitter.data.model.AnimatedGltfModelSource",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.model.GltfModelSource",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.model.JsonModelSource",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.model.ResourceMeshSource"
            ),
            "photon:number_function", List.of(
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRGradient",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRRandomColor",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRRandomGradient",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve"
            ),
            "photon:shape", List.of(
                    "com.lowdragmc.photon.client.gameobject.emitter.data.shape.Box",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.shape.Circle",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cone",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.shape.Cylinder",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.shape.Dot",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.shape.Function",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.shape.Mesh",
                    "com.lowdragmc.photon.client.gameobject.emitter.data.shape.Sphere"
            )
    );

    public AutoRegistry(Identifier registryName) {
        super(registryName);
    }

    public static <T> T noArgsInstance(Annotation annotation, Class<? extends T> clazz) {
        try {
            var ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + clazz.getName(), e);
        }
    }

    public static <T> Supplier<T> noArgsCreator(Annotation annotation, Class<? extends T> clazz) {
        return () -> noArgsInstance(annotation, clazz);
    }

    public record Holder<A extends Annotation, C, V>(A annotation, Class<? extends C> clazz, V value) {
        public static <A extends Annotation, C, V> Holder<A, C, V> of(A annotation, Class<? extends C> clazz, V value) {
            return new Holder<>(annotation, clazz, value);
        }
    }

    @SuppressWarnings("rawtypes")
    public static final class LDLibRegisterClient<C extends ILDLRegisterClient, V>
            extends AutoRegistry<LDLRegisterClient, C, V> {

        private LDLibRegisterClient(Identifier registryName) {
            super(registryName);
        }

        @SuppressWarnings("unchecked")
        public static <C extends ILDLRegisterClient, V> LDLibRegisterClient<C, V> create(
                Identifier registryName,
                Class<C> baseClass,
                BiFunction<LDLRegisterClient, Class<? extends C>, V> valueFactory) {
            var reg = new LDLibRegisterClient<C, V>(registryName);
            List<java.lang.String> classNames = BUILTIN_CLASSES.getOrDefault(registryName.toString(), List.of());
            for (java.lang.String className : classNames) {
                try {
                    Class<?> raw = Class.forName(className, false, AutoRegistry.class.getClassLoader());
                    if (!baseClass.isAssignableFrom(raw)) continue;
                    LDLRegisterClient ann = raw.getAnnotation(LDLRegisterClient.class);
                    if (ann == null) continue;
                    Class<? extends C> sub = (Class<? extends C>) raw;
                    reg.registerOrOverride(ann.name(), Holder.of(ann, sub, valueFactory.apply(ann, sub)));
                } catch (Throwable t) {
                    LDLib2.LOGGER.warn("Skipping Photon auto-registry class {} for {}", className, registryName, t);
                }
            }
            return reg;
        }
    }
}
