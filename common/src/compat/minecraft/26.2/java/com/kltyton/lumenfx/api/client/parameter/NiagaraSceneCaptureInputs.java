package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;

/** Scene Capture 2D components supplied by the client renderer or another effect host. */
public record NiagaraSceneCaptureInputs(Map<String, Component> userParameters, @Nullable Component attached,
                                        @Nullable ManagedFactory managed,
                                        @Nullable ManagedAttachment managedAttachment,
                                        Map<String, List<String>> managedHiddenActors) {
    public static final NiagaraSceneCaptureInputs EMPTY = new NiagaraSceneCaptureInputs(Map.of(), null, null);

    public NiagaraSceneCaptureInputs(Map<String, Component> userParameters, @Nullable Component attached,
                                     @Nullable ManagedFactory managed) {
        this(userParameters, attached, managed, null);
    }

    public NiagaraSceneCaptureInputs(Map<String, Component> userParameters, @Nullable Component attached,
                                     @Nullable ManagedFactory managed, @Nullable ManagedAttachment managedAttachment) {
        this(userParameters, attached, managed, managedAttachment, Map.of());
    }

    public NiagaraSceneCaptureInputs {
        var parameters = new TreeMap<String, Component>(SourceNames.ASCII_CASE_INSENSITIVE);
        userParameters.forEach((name, component) -> parameters.put(name, Objects.requireNonNull(component)));
        userParameters = java.util.Collections.unmodifiableMap(parameters);
        var hiddenActors = new TreeMap<String, List<String>>(SourceNames.ASCII_CASE_INSENSITIVE);
        managedHiddenActors.forEach((identity, actors) -> hiddenActors.put(identity, List.copyOf(actors)));
        managedHiddenActors = java.util.Collections.unmodifiableMap(hiddenActors);
    }

    /** Hidden-list overrides use the interface identity passed to ManagedFactory.create. An empty list clears the list. */
    public NiagaraSceneCaptureInputs withManagedHiddenActors(String interfaceIdentity, List<String> actors) {
        var overrides = new TreeMap<String, List<String>>(SourceNames.ASCII_CASE_INSENSITIVE);
        overrides.putAll(managedHiddenActors);
        overrides.put(interfaceIdentity, actors);
        return new NiagaraSceneCaptureInputs(userParameters, attached, managed, managedAttachment, overrides);
    }

    public enum CaptureSource {
        SCS_SceneColorHDR, SCS_SceneColorHDRNoAlpha, SCS_FinalColorLDR, SCS_SceneColorSceneDepth, SCS_SceneDepth,
        SCS_DeviceDepth, SCS_Normal, SCS_BaseColor, SCS_FinalColorHDR, SCS_FinalToneCurveHDR
    }

    public record ManagedSettings(CaptureSource source, int width, int height, String format, boolean perspective,
                                  float fov, float orthoWidth, float lodDistanceFactor, boolean everyFrame,
                                  boolean onMovement, boolean hideAttachment, List<String> hiddenActors, List<String> showOnlyActors) {
        public ManagedSettings {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid Niagara Scene Capture 2D target size");
            Objects.requireNonNull(source);
            Objects.requireNonNull(format);
            hiddenActors = List.copyOf(hiddenActors);
            showOnlyActors = List.copyOf(showOnlyActors);
        }

        public ManagedSettings withHiddenActors(List<String> actors) {
            return new ManagedSettings(source, width, height, format, perspective, fov, orthoWidth,
                    lodDistanceFactor, everyFrame, onMovement, hideAttachment, actors, showOnlyActors);
        }
    }

    public record Pose(Vec3 position, Quaternionfc rotation, Vec3 scale) {
        public Pose {
            Objects.requireNonNull(position);
            rotation = new Quaternionf(rotation);
            Objects.requireNonNull(scale);
        }
        @Override public Quaternionfc rotation() { return new Quaternionf(rotation); }
    }

    /** The source projection uses reversed Z and the capture camera's forward-positive view axes. */
    public record View(Pose pose, Matrix4fc projection, boolean perspective, int width, int height, int mips,
                       TextureParameter texture) {
        public View {
            projection = new Matrix4f(projection);
            Objects.requireNonNull(pose);
            Objects.requireNonNull(texture);
            if (width <= 0 || height <= 0 || mips <= 0)
                throw new IllegalArgumentException("Invalid Niagara Scene Capture 2D view dimensions");
        }
        @Override public Matrix4fc projection() { return new Matrix4f(projection); }
    }

    /** Calls run on the host thread. Capture requests are collected for the next scene render boundary. */
    public interface Component {
        @Nullable View view(float deltaSeconds);
        void move(Pose pose);
        void requestCapture();
    }

    public interface ManagedComponent extends Component, AutoCloseable {
        @Override void close();
    }

    /** Exact host primitive omitted by a managed capture when its hide-attachment option is enabled. */
    public sealed interface ManagedAttachment permits EntityAttachment, BlockEntityAttachment { }

    /** Entity renderer identity remains stable across host snapshots and is not inferred from transform proximity. */
    public record EntityAttachment(UUID identity) implements ManagedAttachment {
        public EntityAttachment { Objects.requireNonNull(identity); }
    }

    /** Block entities do not have a UUID; retain the exact live renderer object instead of matching by position. */
    public record BlockEntityAttachment(BlockEntity identity) implements ManagedAttachment {
        public BlockEntityAttachment { Objects.requireNonNull(identity); }
    }

    @FunctionalInterface
    public interface ManagedFactory {
        ManagedComponent create(String interfaceIdentity, ManagedSettings settings, Pose initialPose);

        default ManagedComponent create(String interfaceIdentity, ManagedSettings settings, Pose initialPose,
                                        @Nullable ManagedAttachment attachment) {
            return create(interfaceIdentity, settings, initialPose);
        }
    }
}
