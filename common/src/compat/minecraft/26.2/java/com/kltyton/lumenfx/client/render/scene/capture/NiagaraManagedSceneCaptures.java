package com.kltyton.lumenfx.client.render.scene.capture;

import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedComponent;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedFactory;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedAttachment;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedSettings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.Pose;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.View;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.texture.NativeColorTarget;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Defers managed capture work to the world render boundary and leases each published image to GPU steps. */
public final class NiagaraManagedSceneCaptures implements ManagedFactory, AutoCloseable {
    public static final NiagaraManagedSceneCaptures INSTANCE = new NiagaraManagedSceneCaptures();

    private final CaptureWorldRenderer renderer = new CaptureWorldRenderer();
    private final Set<Managed> owned = new LinkedHashSet<>();
    private final Set<Managed> pending = new LinkedHashSet<>();

    private NiagaraManagedSceneCaptures() { }

    @Override public ManagedComponent create(String identity, ManagedSettings settings, Pose initialPose) {
        return create(identity, settings, initialPose, null);
    }

    @Override public ManagedComponent create(String identity, ManagedSettings settings, Pose initialPose,
                                              @Nullable ManagedAttachment attachment) {
        RenderSystem.assertOnRenderThread();
        var component = new Managed(identity, settings, initialPose, attachment);
        owned.add(component);
        return component;
    }

    public void renderPending(ClientLevel level) {
        RenderSystem.assertOnRenderThread();
        if (pending.isEmpty()) return;
        var requests = List.copyOf(pending);
        pending.clear();
        for (int index = 0; index < requests.size(); index++) {
            var item = requests.get(index);
            if (item.closed) continue;
            try {
                item.capture(level);
            } catch (RuntimeException | Error failure) {
                for (var remaining : requests.subList(index, requests.size()))
                    if (!remaining.closed) pending.add(remaining);
                throw failure;
            }
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        for (var item : List.copyOf(owned)) item.close();
        pending.clear();
        renderer.close();
    }

    private final class Managed implements ManagedComponent {
        private final String name;
        private final ManagedSettings settings;
        private final @Nullable ManagedAttachment attachment;
        private final Matrix4f projection;
        private Pose pose;
        private @Nullable Image image, spare;
        private boolean closed;

        Managed(String name, ManagedSettings settings, Pose pose, @Nullable ManagedAttachment attachment) {
            this.name = name;
            this.settings = settings;
            this.pose = pose;
            this.attachment = attachment;
            projection = CaptureCamera.projection(settings);
        }

        @Override public View view(float deltaSeconds) {
            RenderSystem.assertOnRenderThread();
            if (closed) throw new IllegalStateException("Niagara capture was released: " + name);
            if (image == null) {
                Image initial = new Image(this);
                try {
                    RenderSystem.getDevice().createCommandEncoder().clearColorTexture(initial.view.texture(), new Vector4f(0, 0, 0, 1));
                    image = initial;
                } catch (RuntimeException | Error failure) {
                    initial.dispose();
                    throw failure;
                }
            }
            return new View(pose, projection, settings.perspective(), settings.width(), settings.height(), 1, image);
        }

        @Override public void move(Pose value) {
            RenderSystem.assertOnRenderThread();
            boolean moved = !pose.position().equals(value.position()) || !pose.rotation().equals(value.rotation()) || !pose.scale().equals(value.scale());
            pose = value;
            if (moved && settings.onMovement()) requestCapture();
        }

        @Override public void requestCapture() {
            RenderSystem.assertOnRenderThread();
            if (!closed) pending.add(this);
        }

        void capture(ClientLevel level) {
            var next = spare;
            spare = null;
            if (next == null) next = new Image(this);
            else next.references = 1;
            try {
                renderer.render(level, pose, settings, projection, next.view, attachment);
            } catch (RuntimeException | Error failure) {
                next.release();
                throw failure;
            }
            var previous = image;
            image = next;
            if (previous != null) previous.release();
        }

        void retire(Image value) {
            if (!closed && spare == null) spare = value;
            else value.dispose();
        }

        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            if (closed) return;
            closed = true;
            owned.remove(this);
            pending.remove(this);
            if (spare != null) { spare.dispose(); spare = null; }
            if (image != null) { image.release(); image = null; }
        }
    }

    private static final class Image implements TextureParameter {
        private final Managed owner;
        private final GpuTextureView view;
        private final Runnable dispose;
        private int references = 1;

        Image(Managed owner) {
            this.owner = owner;
            var settings = owner.settings;
            int format = RenderTargetTextureDefinition.targetFormat(settings.format());
            if (format == 2 || format == 3) {
                var target = new NativeColorTarget(owner.name + " scene capture", settings.width(), settings.height(), format == 3);
                view = target.view();
                dispose = target::close;
            } else {
                var target = new OwnedColorTarget(owner.name + " scene capture", settings.width(), settings.height(),
                        RenderTargetTextureDefinition.gpuFormat(format));
                view = Objects.requireNonNull(target.getColorTextureView());
                dispose = target::close;
            }
        }

        @Override public Snapshot resolve() {
            RenderSystem.assertOnRenderThread();
            if (references == 0) return Snapshot.UNBOUND;
            return TextureParameter.snapshot(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }

        @Override public @Nullable Retention retain(Snapshot snapshot) {
            RenderSystem.assertOnRenderThread();
            if (references == 0 || snapshot.view() != view) return null;
            references++;
            return new Retention(this::release);
        }

        @Override public boolean retainsAcrossFrames() { return true; }

        void release() {
            if (--references == 0) owner.retire(this);
        }

        void dispose() { dispose.run(); }
    }
}
