package com.kltyton.lumenfx.client.component;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.component.ComponentValue.Kind;
import com.kltyton.lumenfx.api.client.component.EffectComponent;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.jspecify.annotations.Nullable;

/** Non-visual scene component and shared transform/lifecycle implementation for host component types. */
public class SceneEffectComponent implements EffectComponent {
    public enum Change { TRANSFORM, VISIBILITY, ACTIVATED, DEACTIVATED, SCENE_VELOCITY_RESET, DESTROYED, PROPERTY }
    private final class Field implements Property {
        private final Kind kind;
        private ComponentValue value;
        private final Runnable changed;
        Field(Kind kind, ComponentValue value, Runnable changed) { this.kind = kind; this.value = value; this.changed = changed; }
        @Override public Kind type() { return kind; }
        @Override public void set(ComponentValue value) {
            requireAlive();
            if (value.kind() != kind) throw new IllegalArgumentException("Component property expects " + kind + ", received " + value.kind());
            this.value = value;
        }
        void apply(ComponentValue value) {
            boolean different = !value.equals(this.value);
            set(value);
            if (different) changed.run();
        }
    }

    protected final ComponentFactory.Context context;
    private final Map<String, Field> fields = new HashMap<>();
    private final Map<String, Setter<?>> setters = new HashMap<>();
    private final List<SceneEffectComponent> children = new ArrayList<>();
    private final List<Consumer<Change>> listeners = new ArrayList<>();
    private final SceneComponentTransform owner = new SceneComponentTransform(), world = new SceneComponentTransform();
    private final Matrix4d previousWorld = new Matrix4d();
    private @Nullable SceneEffectComponent parent;
    private EffectHandle.Pose ownerPose;
    private boolean alive = true, active;

    protected record Template(ComponentValue location, ComponentValue rotation, ComponentValue scale,
                              ComponentValue velocity, boolean hidden) { }

    public static ComponentFactory.Prepared prepare(JsonObject template) {
        Template data = prepareTemplate(template);
        return context -> new SceneEffectComponent(context, data);
    }

    protected static Template prepareTemplate(JsonObject template) {
        return new Template(vector(template, "RelativeLocation", 0), rotation(template), vector(template, "RelativeScale3D", 1),
                vector(template, "ComponentVelocity", 0), template.has("bHiddenInGame") && template.get("bHiddenInGame").getAsBoolean());
    }

    public SceneEffectComponent(ComponentFactory.Context context, JsonObject template) {
        this(context, prepareTemplate(template));
    }

    protected SceneEffectComponent(ComponentFactory.Context context, Template template) {
        this.context = context;
        ownerPose = context.input().pose();
        owner.owner(context.input().pose());
        define("RelativeLocation", template.location(), this::transformChanged);
        define("RelativeRotation", template.rotation(), this::transformChanged);
        define("RelativeScale3D", template.scale(), this::transformChanged);
        define("bAbsoluteLocation", ComponentValue.bool(!context.localSpace()), this::transformChanged);
        define("bAbsoluteRotation", ComponentValue.bool(!context.localSpace()), this::transformChanged);
        define("bAbsoluteScale", ComponentValue.bool(!context.localSpace()), this::transformChanged);
        define("bVisible", ComponentValue.bool(false), () -> changed(Change.VISIBILITY));
        define("bHiddenInGame", ComponentValue.bool(template.hidden()), () -> changed(Change.VISIBILITY));
        define("ComponentVelocity", template.velocity(), () -> changed(Change.PROPERTY));
        setter("K2_SetRelativeLocation", Kind.DOUBLE3, (value, defaults) -> {
            bool(defaults, "bSweep", false); bool(defaults, "bTeleport", false);
            fields.get("relativelocation").apply(value);
        });
        setter("K2_SetRelativeRotation", Kind.ROTATOR, (value, defaults) -> {
            bool(defaults, "bSweep", false); bool(defaults, "bTeleport", false);
            fields.get("relativerotation").apply(value);
        });
        setter("SetRelativeScale3D", Kind.DOUBLE3, (value, defaults) -> fields.get("relativescale3d").apply(value));
        setter("SetVisibility", Kind.BOOL, (value, defaults) -> setVisible(value.number(0) != 0, bool(defaults, "bPropagateToChildren", false)));
        setter("SetHiddenInGame", Kind.BOOL, (value, defaults) -> setHidden(value.number(0) != 0, bool(defaults, "bPropagateToChildren", false)));
        transformChanged();
        previousWorld.set(world.matrix());
    }

    @FunctionalInterface
    protected interface SetterAction { void apply(ComponentValue value, Map<String, String> defaults); }

    protected final void define(String name, ComponentValue initial, Runnable changed) {
        if (fields.putIfAbsent(key(name), new Field(initial.kind(), initial, changed)) != null)
            throw new IllegalArgumentException("Duplicate scene component property " + name);
    }

    protected final void setter(String name, Kind type, SetterAction action) {
        if (setters.putIfAbsent(key(name), new Setter<Void>() {
            @Override public Kind inputType() { return type; }
            @Override public Void invoke(ComponentValue value, Map<String, String> defaults) {
                requireAlive();
                if (value.kind() != type) throw new IllegalArgumentException("Component setter expects " + type + ", received " + value.kind());
                action.apply(value, defaults);
                return null;
            }
        }) != null) throw new IllegalArgumentException("Duplicate scene component setter " + name);
    }

    protected final ComponentValue value(String name) {
        Field field = fields.get(key(name));
        if (field == null) throw new IllegalArgumentException("Unknown component property " + name);
        return field.value;
    }

    protected final void apply(String name, ComponentValue value) {
        Field field = fields.get(key(name));
        if (field == null) throw new IllegalArgumentException("Unknown component property " + name);
        field.apply(value);
    }

    protected final void applyChanged(String name, ComponentValue value) {
        Field field = fields.get(key(name));
        if (field == null) throw new IllegalArgumentException("Unknown component property " + name);
        field.set(value);
        field.changed.run();
    }

    @Override public @Nullable Property property(String path) {
        String name = key(path);
        Field field = fields.get(name);
        if (field != null) return field;
        int separator = name.lastIndexOf('.');
        if (separator < 0) return null;
        Field vector = fields.get(name.substring(0, separator));
        if (vector == null || !vector.kind.doubles() || vector.kind.components() != 3) return null;
        String axisName = name.substring(separator + 1);
        int axis = switch (axisName) {
            case "x", "pitch" -> 0; case "y", "yaw" -> 1; case "z", "roll" -> 2; default -> -1;
        };
        if (axis < 0 || vector.kind == Kind.ROTATOR != List.of("pitch", "yaw", "roll").contains(axisName)) return null;
        return new Property() {
            @Override public Kind type() { return Kind.DOUBLE; }
            @Override public void set(ComponentValue value) {
                if (value.kind() != Kind.DOUBLE) throw new IllegalArgumentException("Nested component property expects a double");
                ComponentValue old = vector.value;
                vector.set(ComponentValue.doubles(vector.kind, axis == 0 ? value.number(0) : old.number(0),
                        axis == 1 ? value.number(0) : old.number(1), axis == 2 ? value.number(0) : old.number(2), 0));
            }
        };
    }
    @Override public @Nullable Setter<?> setter(String name) { return setters.get(key(name)); }
    @Override public boolean alive() { return alive; }
    @Override public boolean active() { return active; }
    public final boolean visible() { return alive && value("bVisible").number(0) != 0 && value("bHiddenInGame").number(0) == 0; }
    public final Matrix4dc sourceTransform() { return world.matrix(); }
    public final Matrix4d sourceTransformNoScale(Matrix4d output) { return world.matrixNoScale(output); }
    protected final org.joml.Vector3d sourceScale(org.joml.Vector3d output) { return world.scale(output); }
    protected final org.joml.Quaterniond sourceRotation(org.joml.Quaterniond output) { return world.rotation(output); }
    public final Matrix4dc previousSourceTransform() { return previousWorld; }
    public final Vec3 position() { var matrix = world.matrix(); return new Vec3(matrix.m30() * .01, matrix.m32() * .01, matrix.m31() * .01); }
    public final void onChange(Consumer<Change> listener) { requireAlive(); listeners.add(java.util.Objects.requireNonNull(listener)); }

    public final void attach(SceneEffectComponent child) {
        requireAlive(); child.requireAlive();
        for (SceneEffectComponent ancestor = this; ancestor != null; ancestor = ancestor.parent)
            if (ancestor == child) throw new IllegalArgumentException("Cyclic component attachment");
        if (child.parent != null) child.parent.children.remove(child);
        child.parent = this; children.add(child); child.transformChanged();
    }

    @Override public void updateAttachment(EffectHandle.Pose pose) {
        requireAlive();
        if (!pose.equals(ownerPose)) { ownerPose = pose; owner.owner(pose); transformChanged(); }
    }
    @Override public void setVisible(boolean visible, boolean propagateToChildren) {
        fields.get("bvisible").apply(ComponentValue.bool(visible));
        if (propagateToChildren) for (var child : children) child.setVisible(visible, true);
    }
    private void setHidden(boolean hidden, boolean propagate) {
        fields.get("bhiddeningame").apply(ComponentValue.bool(hidden));
        if (propagate) for (var child : children) child.setHidden(hidden, true);
    }
    @Override public void activate(boolean reset) {
        requireAlive();
        if (reset || !active) { active = true; changed(Change.ACTIVATED); }
    }
    @Override public void deactivate() { requireAlive(); if (active) { active = false; changed(Change.DEACTIVATED); } }
    @Override public void resetSceneVelocity() { requireAlive(); previousWorld.set(world.matrix()); changed(Change.SCENE_VELOCITY_RESET); }
    @Override public final void finishRenderFrame(boolean paused) {
        if (!paused) previousWorld.set(world.matrix());
        for (var child : children) child.finishRenderFrame(paused);
    }

    protected final void changed(Change change) { for (var listener : listeners) listener.accept(change); }
    private void transformChanged() {
        world.relative(value("RelativeLocation"), value("RelativeRotation"), value("RelativeScale3D"),
                value("bAbsoluteLocation").number(0) != 0, value("bAbsoluteRotation").number(0) != 0,
                value("bAbsoluteScale").number(0) != 0, parent == null ? owner : parent.world);
        for (var child : children) child.transformChanged();
        changed(Change.TRANSFORM);
    }

    @Override public void close() {
        if (!alive) return;
        deactivate();
        for (var child : List.copyOf(children)) child.close();
        children.clear();
        if (parent != null) { parent.children.remove(this); parent = null; }
        alive = false; changed(Change.DESTROYED); listeners.clear();
    }

    protected final void requireAlive() { if (!alive) throw new IllegalStateException("Scene component was destroyed"); }
    protected static boolean bool(Map<String, String> values, String name, boolean fallback) {
        String value = values.get(name);
        if (value == null) return fallback;
        if (value.equalsIgnoreCase("true") || value.equals("1")) return true;
        if (value.equalsIgnoreCase("false") || value.equals("0")) return false;
        throw new IllegalArgumentException("Invalid boolean component setter argument " + name + ": " + value);
    }
    private static ComponentValue vector(JsonObject template, String name, double fallback) {
        JsonObject vector = template.getAsJsonObject(name);
        return ComponentValue.doubles(Kind.DOUBLE3, number(vector, "X", fallback), number(vector, "Y", fallback), number(vector, "Z", fallback), 0);
    }
    private static ComponentValue rotation(JsonObject template) {
        JsonObject value = template.getAsJsonObject("RelativeRotation");
        return ComponentValue.doubles(Kind.ROTATOR, number(value, "Pitch", 0), number(value, "Yaw", 0), number(value, "Roll", 0), 0);
    }
    protected static double number(@Nullable JsonObject value, String name, double fallback) { return value != null && value.has(name) ? value.get(name).getAsDouble() : fallback; }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}
