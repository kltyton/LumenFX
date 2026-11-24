package com.kltyton.lumenfx.client.simulation.niagara.interfaces.view;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.CaptureSource;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.Component;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedComponent;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedFactory;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedSettings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.Pose;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.math.NativeSceneMath;
import com.kltyton.lumenfx.client.render.scene.capture.NiagaraManagedSceneCaptures;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Separates the source VM state from component work on the host thread. */
public final class NiagaraSceneCapture implements AutoCloseable {
    enum Mode { UserParameterThenAttachParent, UserParameterOnly, AttachParentOnly, Managed }
    enum OffsetMode { Disabled, RelativeLocal, RelativeWorld, AbsoluteWorld }
    public enum Function { GetTextureSize, Project, Deproject, GetCaptureTransform, SetCaptureTransform, SetRequestCapture }

    @FunctionalInterface public interface Input { int word(int component, int lane); }
    @FunctionalInterface public interface Output { void word(int component, int lane, int value); }
    public record Publication(NiagaraSceneCaptureView view, TextureParameter.Snapshot texture,
                              TextureParameter.@Nullable Retention retention) implements AutoCloseable {
        @Override public void close() { if (retention != null) retention.close(); }
    }

    private record Definition(Mode mode, String parameter, boolean autoMove, OffsetMode locationMode, OffsetMode rotationMode,
                              Vec3 location, Vec3 rotation, ManagedSettings managed) {
        static Definition read(JsonObject source) {
            var managed = source.getAsJsonObject("managed");
            var settings = new ManagedSettings(CaptureSource.valueOf(managed.get("source").getAsString()),
                    managed.get("width").getAsInt(), managed.get("height").getAsInt(), managed.get("format").getAsString(),
                    managed.get("projection").getAsString().equals("Perspective"), managed.get("fov").getAsFloat(),
                    managed.get("ortho_width").getAsFloat(), managed.get("lod_distance_factor").getAsFloat(),
                    managed.get("every_frame").getAsBoolean(), managed.get("on_movement").getAsBoolean(),
                    managed.get("hide_attach").getAsBoolean(), names(managed, "hidden_actors"), names(managed, "show_only_actors"));
            return new Definition(Mode.valueOf(source.get("mode").getAsString()), source.get("parameter").getAsString(),
                    source.get("auto_move").getAsBoolean(), OffsetMode.valueOf(source.get("location_mode").getAsString()),
                    OffsetMode.valueOf(source.get("rotation_mode").getAsString()), vector(source, "location"),
                    vector(source, "rotation"), settings);
        }
        private static Vec3 vector(JsonObject data, String name) {
            var values = data.getAsJsonArray(name);
            return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
        }
        private static List<String> names(JsonObject data, String name) {
            var names = new ArrayList<String>();
            for (var element : data.getAsJsonArray(name)) names.add(element.getAsString());
            return names;
        }
    }

    private final String identity;
    private final Definition definition;
    private @Nullable Component component;
    private @Nullable ManagedComponent managed;
    private @Nullable ManagedFactory factory;
    private @Nullable ManagedSettings managedSettings;
    private NiagaraSceneCaptureInputs.@Nullable ManagedAttachment managedAttachment;
    private NiagaraSceneCaptureView view = new NiagaraSceneCaptureView();
    private TextureParameter texture = TextureParameter.unbound();
    private Vec3 tile = Vec3.ZERO;
    private Vec3 position = Vec3.ZERO, scale = new Vec3(1, 1, 1);
    private final Quaternionf rotation = new Quaternionf();
    private @Nullable Quaternionf rotationOffset;
    private boolean transformChanged, captureRequested;

    public NiagaraSceneCapture(String identity, JsonObject definition) {
        this.identity = identity;
        this.definition = Definition.read(definition);
    }

    public void begin(NiagaraSceneCaptureInputs inputs, EffectHandle.Pose owner, Vec3 origin, float deltaSeconds) {
        var ownerPose = new Pose(new Vec3(owner.position().x * 100, owner.position().z * 100, owner.position().y * 100),
                new Quaternionf(-owner.x(), -owner.z(), -owner.y(), owner.w()), new Vec3(owner.scaleX(), owner.scaleZ(), owner.scaleY()));
        tile = origin;
        transformChanged = false;
        captureRequested = false;
        boolean created = false;
        if (definition.mode() == Mode.Managed) {
            var selectedFactory = inputs.managed() == null ? NiagaraManagedSceneCaptures.INSTANCE : inputs.managed();
            var settings = definition.managed();
            var hidden = inputs.managedHiddenActors().get(identity);
            if (hidden != null && !hidden.equals(settings.hiddenActors())) settings = settings.withHiddenActors(hidden);
            var attachment = inputs.managedAttachment();
            if (selectedFactory != factory || !settings.equals(managedSettings) || !Objects.equals(attachment, managedAttachment)) {
                closeManaged();
                factory = selectedFactory;
                managedSettings = settings;
                managedAttachment = attachment;
            }
            if (managed == null && factory != null) {
                managed = factory.create(identity, settings, ownerPose, attachment);
                created = true;
            }
            component = managed;
            captureRequested = settings.everyFrame() || created;
        } else {
            component = definition.mode() == Mode.AttachParentOnly ? null : inputs.userParameters().get(definition.parameter());
            if (component == null && definition.mode() != Mode.UserParameterOnly) component = inputs.attached();
        }
        if (component != null) {
            if (definition.autoMove() || created) component.move(autoMove(ownerPose));
            refreshView(deltaSeconds);
        } else texture = TextureParameter.unbound();
    }

    private Pose autoMove(Pose owner) {
        Vec3 offset = definition.location();
        Vec3 location = switch (definition.locationMode()) {
            case Disabled -> owner.position();
            case RelativeWorld -> owner.position().add(offset);
            case AbsoluteWorld -> offset;
            case RelativeLocal -> {
                var delta = offset.subtract(owner.position());
                var rotated = owner.rotation().transformInverse(new Vector3f((float)delta.x, (float)delta.y, (float)delta.z));
                yield new Vec3(rotated.x * reciprocal(owner.scale().x), rotated.y * reciprocal(owner.scale().y),
                        rotated.z * reciprocal(owner.scale().z));
            }
        };
        var orientation = new Quaternionf(owner.rotation());
        if (definition.rotationMode() != OffsetMode.Disabled) {
            if (rotationOffset == null) {
                Vec3 euler = definition.rotation();
                double[] value = new double[4];
                NativeSceneMath.rotatorQuaternion(euler.x, euler.y, euler.z, value);
                rotationOffset = new Quaternionf((float)value[0], (float)value[1], (float)value[2], (float)value[3]);
            }
            switch (definition.rotationMode()) {
                case RelativeLocal -> rotationOffset.mul(orientation, orientation);
                case RelativeWorld -> orientation.mul(rotationOffset);
                case AbsoluteWorld -> orientation.set(rotationOffset);
                default -> { }
            }
        }
        return new Pose(location, orientation, owner.scale());
    }

    private static double reciprocal(double scale) { return Math.abs(scale) <= 1.e-8 ? 0 : 1 / scale; }

    private void updatePose(Pose pose) {
        position = pose.position().subtract(tile);
        rotation.set(pose.rotation());
        scale = pose.scale();
    }

    private void refreshView(float deltaSeconds) {
        var snapshot = component == null ? null : component.view(deltaSeconds);
        if (snapshot == null) {
            texture = TextureParameter.unbound();
            return;
        }
        var pose = snapshot.pose();
        updatePose(pose);
        view = new NiagaraSceneCaptureView(pose.position(), pose.rotation(), tile, snapshot.projection(),
                snapshot.perspective(), snapshot.width(), snapshot.height(), snapshot.mips());
        texture = snapshot.texture();
    }

    public synchronized void execute(Function function, int count, Input input, Output output) {
        for (int lane = 0; lane < count; lane++) {
            switch (function) {
                case GetTextureSize -> {
                    output.word(0, lane, view.width());
                    output.word(1, lane, view.height());
                    output.word(2, lane, view.mips());
                }
                case Project -> write(output, lane, view.project(value(input, 0, lane), value(input, 1, lane), value(input, 2, lane)));
                case Deproject -> write(output, lane, view.deproject(value(input, 0, lane), value(input, 1, lane), value(input, 2, lane)));
                case GetCaptureTransform -> {
                    output.word(0, lane, Float.floatToRawIntBits((float)position.x));
                    output.word(1, lane, Float.floatToRawIntBits((float)position.y));
                    output.word(2, lane, Float.floatToRawIntBits((float)position.z));
                    writeRotation(output, lane, rotation);
                }
                case SetCaptureTransform -> {
                    if ((input.word(0, lane) & 255) == 0) continue;
                    position = new Vec3(value(input, 1, lane), value(input, 2, lane), value(input, 3, lane));
                    rotation.set(value(input, 4, lane), value(input, 5, lane), value(input, 6, lane), value(input, 7, lane));
                    transformChanged = true;
                }
                case SetRequestCapture -> captureRequested |= (input.word(0, lane) & 255) != 0;
            }
        }
    }

    private static float value(Input input, int component, int lane) { return Float.intBitsToFloat(input.word(component, lane)); }
    private static void write(Output output, int lane, Vector3f value) {
        output.word(0, lane, Float.floatToRawIntBits(value.x));
        output.word(1, lane, Float.floatToRawIntBits(value.y));
        output.word(2, lane, Float.floatToRawIntBits(value.z));
    }
    private static void writeRotation(Output output, int lane, Quaternionfc value) {
        output.word(3, lane, Float.floatToRawIntBits(value.x()));
        output.word(4, lane, Float.floatToRawIntBits(value.y()));
        output.word(5, lane, Float.floatToRawIntBits(value.z()));
        output.word(6, lane, Float.floatToRawIntBits(value.w()));
    }

    public void postSimulate(float deltaSeconds) {
        if (component != null) {
            if (transformChanged) {
                component.move(new Pose(position.add(tile), rotation, scale));
                refreshView(deltaSeconds);
            }
            if (captureRequested) component.requestCapture();
        }
        captureRequested = transformChanged = false;
    }

    public Publication publish(boolean deferred) {
        var image = texture.resolve();
        if (image.dimension() != TextureParameter.Dimension.D2)
            throw new IllegalArgumentException("Niagara Scene Capture 2D requires a two-dimensional target: " + identity);
        if (!image.bound()) return new Publication(view, image, null);
        var retained = texture.retain(image);
        if (retained == null && deferred)
            throw new IllegalStateException("Niagara Scene Capture 2D target cannot be held for a deferred GPU step: " + identity);
        return new Publication(view, retained == null ? image : retained.snapshot(image), retained);
    }

    public TextureParameter.@Nullable Snapshot exposedTexture(String variable) {
        if (managed == null || !SourceNames.equal(variable, "SceneCapture")) return null;
        var current = managed.view(0);
        if (current == null) return null;
        var image = current.texture().resolve();
        return image.bound() && image.dimension() == TextureParameter.Dimension.D2 ? image : null;
    }

    private void closeManaged() {
        if (managed != null) managed.close();
        managed = null;
    }

    @Override public void close() {
        closeManaged();
        component = null;
        factory = null;
        managedSettings = null;
        managedAttachment = null;
        texture = TextureParameter.unbound();
    }
}
