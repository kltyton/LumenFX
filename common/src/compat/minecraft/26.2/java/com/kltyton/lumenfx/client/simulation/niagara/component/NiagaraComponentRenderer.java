package com.kltyton.lumenfx.client.simulation.niagara.component;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.VfxComponents;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.ComponentOwner;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.component.EffectComponent;
import com.kltyton.lumenfx.api.client.component.PrimitiveOwnerVisibility;
import com.kltyton.lumenfx.client.component.lifecycle.ComponentWorlds;
import com.kltyton.lumenfx.client.component.light.DirectionalLightEffectComponent;
import com.kltyton.lumenfx.client.component.mesh.StaticMeshEffectComponent;
import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentDraw;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.component.ComponentRendererSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Post-system-tick component allocation. The owning client thread alone touches scene objects. */
public final class NiagaraComponentRenderer implements AutoCloseable {
    private record Reader(ComponentRendererSettings.Binding binding, NiagaraDefinition.@Nullable Attribute attribute) {
        @Nullable ComponentValue read(VectorDataSet data, int row) {
            if (attribute == null) return null;
            var kind = java.util.Objects.requireNonNull(binding.sourceType());
            int base = attribute.component(), type = attribute.type(), count = kind.components();
            return new ComponentValue(kind, word(data, row, type, base),
                    count > 1 ? word(data, row, type, base + 1) : 0,
                    count > 2 ? word(data, row, type, base + 2) : 0,
                    count > 3 ? word(data, row, type, base + 3) : 0);
        }
        private static int word(VectorDataSet data, int row, int type, int component) {
            return data.component(type, component)[row];
        }
    }
    private record Access(EffectComponent.@Nullable Property property, EffectComponent.@Nullable Setter<?> setter,
                          ComponentValue.@Nullable Kind target, boolean ignoreConversion) { }
    private static final class Entry {
        EffectComponent component;
        EffectHandle.@Nullable Pose attachment;
        int particle = -1;
        double lastActive;
        final Map<String, Access> access = new HashMap<>();
        Entry(EffectComponent component) { this.component = component; }
    }

    private final ComponentRendererSettings settings;
    private final ComponentFactory.Prepared factory;
    private final boolean localSpace;
    private final List<Reader> readers;
    private final Map<String, ComponentRendererSettings.Binding> firstBindings = new LinkedHashMap<>();
    private final int enabledComponent, visibilityComponent, idComponent;
    private final List<Entry> pool = new ArrayList<>();
    private @Nullable ComponentOwner owner;
    private @Nullable EffectHandle host;
    private final Set<Integer> everCreated;
    private final Map<Integer, Integer> used = new LinkedHashMap<>(), retained = new HashMap<>();
    private final List<Integer> free = new ArrayList<>();

    public NiagaraComponentRenderer(ComponentRendererSettings settings, List<NiagaraDefinition.Attribute> attributes, boolean localSpace) {
        this(settings, attributes, localSpace, new HashSet<>());
    }

    public NiagaraComponentRenderer(ComponentRendererSettings settings, List<NiagaraDefinition.Attribute> attributes,
                                    boolean localSpace, Set<Integer> everCreated) {
        this.settings = settings; this.localSpace = localSpace;
        factory = settings.preparedFactory() != null ? settings.preparedFactory()
                : VfxComponents.factory(settings.sourceClass()).prepare(settings.template());
        this.everCreated = Objects.requireNonNull(everCreated);
        enabledComponent = integerComponent(attributes, settings.enabledAttribute(), ComponentValue.Kind.BOOL);
        visibilityComponent = integerComponent(attributes, settings.visibilityAttribute(), ComponentValue.Kind.INT);
        idComponent = integerComponent(attributes, "UniqueID", ComponentValue.Kind.INT);
        List<Reader> readers = new ArrayList<>();
        for (var binding : settings.bindings()) {
            firstBindings.putIfAbsent(key(binding.property()), binding);
            var attribute = attribute(attributes, binding.attribute());
            int storage = binding.sourceType() == ComponentValue.Kind.INT || binding.sourceType() == ComponentValue.Kind.BOOL ? 1 : 0;
            if (binding.sourceType() == null) attribute = null;
            else if (attribute != null && (attribute.type() != storage || attribute.count() != binding.sourceType().components()
                    || sourceKind(attribute.sourceType()) != binding.sourceType())) attribute = null;
            readers.add(new Reader(binding, attribute));
        }
        this.readers = List.copyOf(readers);
    }

    public void postTick(ClientLevel level, VectorDataSet data, int spawned, EffectHandle.Input input,
                         Vec3 simulationOrigin, double realTime, float inactiveTimeLimit, boolean rendererEnabled,
                         PrimitiveOwnerVisibility ownerVisibility, @Nullable EffectHandle host) {
        requireClientThread();
        this.host = host;
        used.clear(); retained.clear(); free.clear();
        if (settings.assignByParticleId()) {
            for (int i = 0; i < pool.size(); i++) {
                int id = pool.get(i).particle;
                if (id >= 0) used.put(id, i); else free.add(i);
            }
            for (int row = 0; row < data.count(); row++) {
                int id = integer(data, row, idComponent, -1);
                Integer index = used.remove(id);
                if (index == null) continue;
                if (enabled(data, row, rendererEnabled)) retained.put(id, index);
                else release(index);
            }
            for (int index : used.values()) release(index);
        }
        int active = 0;
        for (int row = 0; row < data.count(); row++) {
            if (!enabled(data, row, rendererEnabled)) continue;
            int id = settings.assignByParticleId() ? integer(data, row, idComponent, -1) : -1;
            Integer retainedIndex = settings.assignByParticleId() ? retained.remove(id) : null;
            int index = retainedIndex == null ? -1 : retainedIndex;
            if (settings.assignByParticleId() && index < 0 && settings.firstParticleFrameOnly()
                    && !everCreated.contains(id) && row < data.count() - spawned) continue;
            if (index < 0 && active + retained.size() >= settings.countLimit()) continue;
            boolean acquired = index < 0;
            if (acquired) {
                if (!settings.assignByParticleId()) index = active < pool.size() ? active : -1;
                else if (!free.isEmpty()) index = free.removeLast();
            }
            Entry entry;
            if (index < 0) {
                entry = new Entry(create(level, input, simulationOrigin));
                pool.add(entry);
            } else {
                entry = pool.get(index);
                if (!entry.component.alive()) {
                    ComponentWorlds.destroyComponent(Objects.requireNonNull(owner), entry.component);
                    entry.component = create(level, input, simulationOrigin);
                    entry.attachment = null;
                    entry.access.clear();
                }
            }
            updateAttachment(entry, input.pose());
            bind(entry, data, row, simulationOrigin);
            if (settings.inheritOwnerVisibility() && entry.component instanceof EffectComponent.Primitive primitive)
                primitive.setOwnerVisibility(ownerVisibility);
            if ((!settings.assignByParticleId() || acquired || !settings.activateOnlyNew()) && !entry.component.active()) {
                entry.component.setVisible(true, true);
                entry.component.activate(false);
                entry.component.resetSceneVelocity();
            }
            entry.particle = id; entry.lastActive = realTime;
            if (settings.firstParticleFrameOnly()) everCreated.add(id);
            if (++active >= settings.countLimit()) break;
        }
        if (active < pool.size()) for (int index = 0; index < pool.size(); index++) {
            Entry entry = pool.get(index);
            if (settings.assignByParticleId() ? entry.particle >= 0 : index < active) continue;
            if (!entry.component.alive() || realTime - entry.lastActive >= inactiveTimeLimit) {
                int last = pool.size() - 1;
                if (index != last) pool.set(index, pool.get(last));
                pool.removeLast(); index--;
                ComponentWorlds.destroyComponent(Objects.requireNonNull(owner), entry.component);
            } else deactivate(entry.component);
        }
    }

    private EffectComponent create(ClientLevel level, EffectHandle.Input input, Vec3 simulationOrigin) {
        if (owner == null || !owner.alive())
            owner = ComponentWorlds.createOwner(level, this::viewOwners);
        var component = factory.create(new ComponentFactory.Context(level, input, localSpace, simulationOrigin, owner));
        owner.attach(component);
        return component;
    }

    private Set<UUID> viewOwners() { return host == null ? Set.of() : host.niagaraViewOwners(); }

    public void updateAttachment(EffectHandle.Pose pose) {
        requireClientThread();
        for (Entry entry : pool) if (entry.component.alive()) updateAttachment(entry, pose);
    }

    private static void updateAttachment(Entry entry, EffectHandle.Pose pose) {
        if (!pose.equals(entry.attachment)) { entry.component.updateAttachment(pose); entry.attachment = pose; }
    }

    private void bind(Entry entry, VectorDataSet data, int row, Vec3 simulationOrigin) {
        for (Reader reader : readers) {
            ComponentValue value = reader.read(data, row);
            if (value == null) continue;
            var binding = reader.binding();
            Access access = entry.access.computeIfAbsent(key(binding.property()), name -> resolve(entry.component, firstBindings.get(name)));
            var target = access.target();
            if (target != null && target != value.kind() && (!access.ignoreConversion() || target.doubles()))
                value = ComponentValueConversion.convert(value, target, simulationOrigin);
            if (access.setter() != null) {
                access.setter().invoke(value, binding.setterDefaults());
            } else if (access.property() != null) {
                access.property().set(value);
            }
        }
    }

    private static Access resolve(EffectComponent component, ComponentRendererSettings.Binding binding) {
        EffectComponent.Setter<?> setter = binding.metadataSetter() == null ? null : component.setter(binding.metadataSetter());
        if (setter == null) {
            String name = binding.property();
            if (binding.propertyType() == ComponentValue.Kind.BOOL && name.startsWith("b")) name = name.substring(1);
            setter = component.setter("Set" + name);
            if (setter == null) setter = component.setter("K2_Set" + name);
        }
        var declared = binding.propertyType();
        boolean ignore = setter != null && declared != null && setter.inputType() != declared && setter.inputType() == binding.sourceType();
        if (setter != null && declared != null && setter.inputType() != declared
                && !ComponentValueConversion.widens(declared, setter.inputType()) && !ignore) setter = null;
        var property = setter == null ? component.property(binding.property()) : null;
        var actual = setter != null ? setter.inputType() : property == null ? null : property.type();
        // Bindings saved with a simulation-width type still target the actual LWC component field.
        var target = declared == null || ComponentValueConversion.widens(declared, actual) ? actual : declared;
        return new Access(property, setter, target, ignore);
    }

    private boolean enabled(VectorDataSet data, int row, boolean rendererEnabled) {
        return rendererEnabled && integer(data, row, enabledComponent, -1) != 0
                && (visibilityComponent < 0 || data.integer(visibilityComponent, row) == settings.visibilityTag());
    }
    private void release(int index) { Entry entry = pool.get(index); deactivate(entry.component); entry.particle = -1; free.add(index); }
    private static void deactivate(EffectComponent component) {
        if (component.alive() && component.active()) { component.deactivate(); component.setVisible(false, true); }
    }
    private static int integer(VectorDataSet data, int row, int component, int fallback) { return component < 0 ? fallback : data.integer(component, row); }
    private static int integerComponent(List<NiagaraDefinition.Attribute> attributes, String name, ComponentValue.Kind kind) {
        var attribute = attribute(attributes, name);
        return attribute != null && attribute.type() == 1 && attribute.count() == 1 && sourceKind(attribute.sourceType()) == kind ? attribute.component() : -1;
    }
    private static NiagaraDefinition.@Nullable Attribute attribute(List<NiagaraDefinition.Attribute> attributes, String name) {
        if (name.isEmpty() || SourceNames.equal(name, "None")) return null;
        String bare = name.regionMatches(true, 0, "Particles.", 0, 10) ? name.substring(10) : name;
        for (var attribute : attributes)
            if (SourceNames.equal(attribute.name(), name) || SourceNames.equal(attribute.name(), bare)
                    || SourceNames.equal(attribute.name(), "Particles." + bare)) return attribute;
        return null;
    }
    private static ComponentValue.@Nullable Kind sourceKind(String path) {
        return switch (key(path)) {
            case "/script/niagara.niagarafloat" -> ComponentValue.Kind.FLOAT;
            case "/script/niagara.niagaraint32" -> ComponentValue.Kind.INT;
            case "/script/niagara.niagarabool" -> ComponentValue.Kind.BOOL;
            case "/script/niagara.niagaraposition" -> ComponentValue.Kind.POSITION;
            case "/script/coreuobject.vector2f" -> ComponentValue.Kind.FLOAT2;
            case "/script/coreuobject.vector3f" -> ComponentValue.Kind.FLOAT3;
            case "/script/coreuobject.vector4f" -> ComponentValue.Kind.FLOAT4;
            case "/script/coreuobject.linearcolor" -> ComponentValue.Kind.LINEAR_COLOR;
            case "/script/coreuobject.quat4f" -> ComponentValue.Kind.QUAT_FLOAT;
            default -> null;
        };
    }
    private static String key(String name) { return SourceNames.canonical(name); }
    private static void requireClientThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Component renderer requires the client thread");
    }

    /** Render-state recreation preserves the emitter's already-created particle/template identities. */
    public void destroyRenderState() {
        requireClientThread();
        ComponentOwner previousOwner = owner;
        owner = null;
        host = null;
        pool.clear(); used.clear(); retained.clear(); free.clear();
        if (previousOwner != null) previousOwner.destroy();
    }
    public void reset() { everCreated.clear(); destroyRenderState(); }
    public void finishRenderFrame(boolean paused) {
        requireClientThread();
        for (Entry entry : pool) if (entry.component.alive()) entry.component.finishRenderFrame(paused);
    }
    public void collectPointLights(com.kltyton.lumenfx.effect.EffectDefinition effect,
                                   List<com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight> output) {
        requireClientThread();
        for (Entry entry : pool) if (entry.component.alive()
                && entry.component instanceof com.kltyton.lumenfx.client.component.light.PointLightEffectComponent point) {
            var frame = point.frame(-1, 0);
            if (frame != null) output.add(new com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight(
                    effect, point.lightIdentity(), frame, point.iesTexture(), point.lightFunctionMaterial()));
        }
    }
    public void collectDirectionalLights(EffectDefinition effect, List<SceneDirectionalLight> output) {
        requireClientThread();
        for (Entry entry : pool) if (entry.component.alive()
                && entry.component instanceof DirectionalLightEffectComponent light) {
            var frame = light.frame(effect);
            if (frame != null) output.add(frame);
        }
    }
    public void collectDecals(com.kltyton.lumenfx.effect.EffectDefinition effect,
                              List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> output) {
        requireClientThread();
        for (Entry entry : pool) if (entry.component.alive()
                && entry.component instanceof com.kltyton.lumenfx.client.component.decal.DecalEffectComponent decal) {
            var frame = decal.frame(effect);
            if (frame != null) output.add(frame);
        }
    }
    public void collectMeshes(EffectDefinition effect, @Nullable UUID viewActor, List<StaticMeshComponentDraw> output) {
        requireClientThread();
        for (Entry entry : pool) if (entry.component instanceof StaticMeshEffectComponent mesh) {
            var draw = mesh.capture(effect, viewActor);
            if (draw != null) output.add(draw);
        }
    }
    @Override public void close() { destroyRenderState(); }
}
