package com.kltyton.lumenfx.client.compat.iris;

import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.client.compat.shaderpack.FrameListener;
import com.kltyton.lumenfx.client.compat.shaderpack.FrameOwnership;
import com.kltyton.lumenfx.client.compat.shaderpack.HostFrameContext;
import com.kltyton.lumenfx.client.compat.shaderpack.HostFrameContextProvider;
import com.kltyton.lumenfx.client.compat.iris.mixin.IrisRenderingPipelineAccessor;
import com.kltyton.lumenfx.client.compat.iris.material.IrisMaterialCapture;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackKind;
import com.kltyton.lumenfx.client.event.ClientVfxEvents;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.EnumSet;
import java.util.Objects;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import org.lwjgl.opengl.GL11C;
import org.joml.Matrix4f;
import net.irisshaders.iris.uniforms.CapturedRenderingState;

public final class IrisCompat {
    private static final int MINIMUM_API_REVISION = 4;
    private static final Object PROFILE_LOCK = new Object();
    private static final FunctionReturn JITTER_VALUE = new FunctionReturn();

    private static volatile FrameListener listener;
    private static volatile HostFrameContextProvider contextProvider;
    private static volatile Throwable lastFailure;
    private static volatile boolean active;
    private static volatile String inactiveReason = "Iris bridge not initialised";
    private static volatile ShaderPack cachedPack;
    private static volatile ProgramSet cachedPrograms;
    private static volatile InternalTextureFormat cachedColorFormat;
    private static volatile IrisShaderProfile cachedProfile;

    private IrisCompat() {
    }

    public static synchronized boolean initialize() {
        if (active) {
            return true;
        }
        final int apiRevision = IrisApi.getInstance().getMinorApiRevision();
        if (apiRevision < MINIMUM_API_REVISION) {
            active = false;
            inactiveReason = "Unsupported Iris API revision: " + apiRevision;
            return false;
        }
        active = true;
        inactiveReason = "";
        return true;
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isShaderPackInUse() {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    public static com.mojang.blaze3d.textures.GpuTexture activeSceneDepth() {
        if (!active) return null;
        final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IrisRenderingPipeline irisPipeline)) return null;
        final RenderTargets targets = ((IrisRenderingPipelineAccessor) (Object) irisPipeline).lumenfx$renderTargets();
        return targets == null ? null : targets.getDepthTexture();
    }

    public static String inactiveReason() {
        return inactiveReason;
    }

    public static synchronized boolean installFrameListener(final FrameListener installed) {
        Objects.requireNonNull(installed, "listener");
        if (!active) {
            return false;
        }
        if (listener == installed) {
            return true;
        }
        if (listener != null) {
            return false;
        }
        listener = installed;
        return true;
    }

    public static synchronized boolean installHostFrameContextProvider(final HostFrameContextProvider installed) {
        Objects.requireNonNull(installed, "provider");
        if (!active) {
            return false;
        }
        if (contextProvider == installed) {
            return true;
        }
        if (contextProvider != null) {
            return false;
        }
        contextProvider = installed;
        return true;
    }

    public static void clearFrameListener() {
        listener = null;
    }

    public static void clearHostFrameContextProvider() {
        contextProvider = null;
    }

    public static Throwable lastFailure() {
        return lastFailure;
    }

    public static synchronized void shutdown() {
        active = false;
        inactiveReason = "Iris bridge shut down";
        listener = null;
        contextProvider = null;
        cachedPack = null;
        cachedPrograms = null;
        cachedProfile = null;
    }

    public static FrameOwnership prepareFrameOwnership() {
        if (!active) {
            return FrameOwnership.inactive();
        }
        try {
            final WorldRenderingPipeline pipeline = Iris.getPipelineManager()
                    .preparePipeline(Iris.getCurrentDimension());
            if (pipeline == null) {
                return FrameOwnership.inactive();
            }
            final boolean external = pipeline instanceof IrisRenderingPipeline;
            return new FrameOwnership(external, external, pipeline, pipeline.getClass().getName());
        } catch (RuntimeException | LinkageError error) {
            lastFailure = error;
            throw error;
        }
    }

    private static IrisShaderProfile profile(final ShaderPack pack, final ProgramSet programs,
                                               final InternalTextureFormat colorFormat) {
        if (pack == cachedPack && programs == cachedPrograms && colorFormat == cachedColorFormat) {
            final IrisShaderProfile cached = cachedProfile;
            if (cached != null) {
                return cached;
            }
        }
        synchronized (PROFILE_LOCK) {
            if (pack != cachedPack || programs != cachedPrograms || colorFormat != cachedColorFormat
                    || cachedProfile == null) {
                cachedProfile = IrisShaderProfile.of(pack, programs, colorFormat);
                cachedPack = pack;
                cachedPrograms = programs;
                cachedColorFormat = colorFormat;
            }
            return cachedProfile;
        }
    }

    public static void beforeComposite(final IrisRenderingPipeline pipeline) {
        publishFrame(pipeline, false);
    }

    public static void afterOpaque(final IrisRenderingPipeline pipeline) {
        if (ClientVfxEvents.hasShaderpackOpaqueWork(CapturedRenderingState.INSTANCE.getGbufferModelView())) {
            publishFrame(pipeline, true);
        }
    }

    private static void publishFrame(final IrisRenderingPipeline pipeline, boolean opaque) {
        if (!active) {
            return;
        }
        final CompositeRenderer compositeRenderer = opaque || pipeline == null ? null
                : ((IrisRenderingPipelineAccessor) (Object) pipeline).lumenfx$compositeRenderer();
        final IrisCompositeStageHolder stageHolder = compositeRenderer instanceof IrisCompositeStageHolder holder
                ? holder : null;
        if (stageHolder != null) {
            stageHolder.lumenfx$setActiveFrameContext(null);
        }
        final FrameListener installed = listener;
        if (installed == null) {
            return;
        }
        try {
            final RenderTargets targets = ((IrisRenderingPipelineAccessor) (Object) pipeline).lumenfx$renderTargets();
            if (targets == null) {
                return;
            }
            final RenderTarget color = targets.get(0);
            if (color == null) {
                return;
            }
            final HostFrameContextProvider provider = contextProvider;
            if (provider == null) {
                return;
            }
            final HostFrameContext capturedHost = provider.capture();
            if (capturedHost == null || !capturedHost.available()) {
                return;
            }
            final var irisState = CapturedRenderingState.INSTANCE;
            final Matrix4f materialProjection = new Matrix4f(Objects.requireNonNull(
                    irisState.getGbufferProjection(), "Iris gbuffer projection matrix"));
            final Matrix4f renderProjection = new Matrix4f(materialProjection);
            if (com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()) {
                renderProjection.m02(0.5F * (materialProjection.m03() - materialProjection.m02()));
                renderProjection.m12(0.5F * (materialProjection.m13() - materialProjection.m12()));
                renderProjection.m22(0.5F * (materialProjection.m23() - materialProjection.m22()));
                renderProjection.m32(0.5F * (materialProjection.m33() - materialProjection.m32()));
            } else {
                renderProjection.m02(-materialProjection.m02());
                renderProjection.m12(-materialProjection.m12());
                renderProjection.m22(-materialProjection.m22());
                renderProjection.m32(-materialProjection.m32());
            }
            final HostFrameContext host = new HostFrameContext(capturedHost.hostFrameIdentity(), capturedHost.worldIdentity(),
                    new Matrix4f(Objects.requireNonNull(irisState.getGbufferModelView(), "Iris gbuffer model-view matrix")),
                    renderProjection,
                    capturedHost.cameraX(), capturedHost.cameraY(), capturedHost.cameraZ(),
                    capturedHost.viewportWidth(), capturedHost.viewportHeight(), true);
            final ShaderPack pack = ((IrisRenderingPipelineAccessor) (Object) pipeline).lumenfx$pack();
            final ProgramSet programs = pack.getProgramSet(Iris.getCurrentDimension());
            final boolean compositePreFlip = Boolean.TRUE.equals(programs.getPackDirectives()
                    .getExplicitFlips("composite_pre").get(0));
            final boolean altSide = opaque
                    ? pipeline.getFlippedAfterPrepare().contains(0)
                    : (pipeline.getFlippedAfterTranslucent().contains(0) ^ compositePreFlip);
            final int colorTexture = altSide ? color.getAltTexture() : color.getMainTexture();
            final GpuTexture depth0 = targets.getDepthTexture();
            final GpuTexture depth1 = targets.getDepthTextureNoTranslucents();
            final String packName = Iris.getCurrentPackName();
            final IrisShaderProfile profile = profile(pack, programs, color.getInternalFormat());
            final IrisSceneColor sceneColor = new IrisSceneColor(
                    colorTexture,
                    GL11C.GL_TEXTURE_2D,
                    color.getWidth(),
                    color.getHeight(),
                    color.getInternalFormat(),
                    altSide
            );
            boolean jitterKnown = profile.jitterKnown();
            float jitterX = 0.0F;
            float jitterY = 0.0F;
            float[] temporalSamples = null;
            if (jitterKnown) {
                final float[] jitterAffine = profile.jitterAffine();
                final int sampleCount = profile.temporalSampleCount();
                if (!Float.isFinite(jitterAffine[0]) || !Float.isFinite(jitterAffine[1])
                        || !Float.isFinite(jitterAffine[2]) || !Float.isFinite(jitterAffine[3])
                        || !Float.isFinite(jitterAffine[4]) || !Float.isFinite(jitterAffine[5])) {
                    jitterKnown = false;
                } else if (sampleCount == 1) {
                    temporalSamples = new float[] {0, 1, 0, 0};
                } else {
                    final float[][] table = profile.jitterOffsets();
                    if (table == null || table.length != sampleCount) {
                        jitterKnown = false;
                    } else {
                        final String uniformName = sampleCount == 2 ? "framemod2" : "framemod8";
                        final var uniforms = pipeline.getCustomUniforms();
                        if (!uniforms.hasVariable(uniformName)
                                || !(uniforms.getVariable(uniformName) instanceof CachedUniform frameUniform)) {
                            jitterKnown = false;
                        } else {
                            frameUniform.writeTo(JITTER_VALUE);
                            final int frame;
                            if (frameUniform.getType() == Type.Float && Float.isFinite(JITTER_VALUE.floatReturn)) {
                                frame = (int) JITTER_VALUE.floatReturn;
                            } else if (frameUniform.getType() == Type.Int) {
                                frame = JITTER_VALUE.intReturn;
                            } else {
                                frame = -1;
                            }
                            if (frame < 0 || frame >= table.length) {
                                jitterKnown = false;
                            } else {
                                final float[] offset = table[frame];
                                final float px = offset[0] * jitterAffine[0] + offset[1] * jitterAffine[1] + jitterAffine[2];
                                final float py = offset[0] * jitterAffine[3] + offset[1] * jitterAffine[4] + jitterAffine[5];
                                jitterX = px / host.viewportWidth();
                                jitterY = py / host.viewportHeight();
                                temporalSamples = new float[] {frame, sampleCount, px * .5f, -py * .5f};
                            }
                        }
                    }
                }
            }
            var snapshot = new IrisFrameSnapshot(
                    host,
                    packName,
                    profile.kind(),
                    profile.colourEncoding(),
                    sceneColor,
                    jitterKnown,
                    jitterX,
                    jitterY,
                    depth0,
                    depth1,
                    IrisMaterialCapture.surfaces(profile.rawSpecularEncoding(pack), materialProjection),
                    profile.cameraExposure(),
                    temporalSamples,
                    materialProjection
            );
            if (opaque) {
                installed.onOpaqueFrame(snapshot);
                if (snapshot.packKind() == ShaderpackKind.COMPLEMENTARY_REIMAGINED && snapshot.materialSurfaces() != null) {
                    var capture = WorldHdrCapture.INSTANCE;
                    var projected = capture.sceneDecals();
                    if (projected != null) IrisMaterialCapture.resolveProjectedDecalMaterials(targets,
                            pipeline.getFlippedAfterPrepare(), snapshot.materialSurfaces(),
                            capture.linearTarget().getDepthTextureView(), projected);
                }
            } else {
                final IrisCompositeStagePlanner.StagePlan plan = stageHolder != null
                        ? stageHolder.lumenfx$stagePlan(profile.colourEncoding())
                        : IrisCompositeStagePlanner.StagePlan.fallbackImmediate(0, profile.colourEncoding());
                final boolean moreStagesFollow = plan.hasAnyDeferredStage();
                if (moreStagesFollow) {
                    stageHolder.lumenfx$setActiveFrameContext(new CompositeFrameSession(
                            installed, snapshot, plan));
                }
                try {
                    installed.onBeforeComposite(snapshot, plan.immediateStages(), moreStagesFollow);
                } catch (RuntimeException | LinkageError callbackError) {
                    if (stageHolder != null) {
                        stageHolder.lumenfx$setActiveFrameContext(null);
                    }
                    throw callbackError;
                }
            }
            lastFailure = null;
        } catch (RuntimeException | LinkageError error) {
            lastFailure = error;
            throw error;
        }
    }

    public static boolean requiresCutpoint(final Object rawSession, final int passIndex) {
        if (!(rawSession instanceof CompositeFrameSession session)) {
            return false;
        }
        final IrisCompositeStagePlanner.StagePlan plan = session.plan();
        return (plan.hasDeferredAfterDof() && plan.afterDofPassIndex() == passIndex)
                || (plan.hasDeferredAfterMotionBlur() && plan.afterMotionBlurPassIndex() == passIndex);
    }

    public static void onCompositeCutpoint(final Object rawSession, final RenderTargets targets, final int passIndex) {
        if (!(rawSession instanceof CompositeFrameSession session) || targets == null) {
            return;
        }
        final RenderTarget color = targets.get(0);
        if (color == null) {
            return;
        }
        final IrisCompositeStagePlanner.StagePlan plan = session.plan();
        if (plan.hasDeferredAfterDof() && plan.afterDofPassIndex() == passIndex) {
            final EnumSet<TranslucencyPass> stages = plan.afterDofStages();
            if (!stages.isEmpty()) {
                dispatchCompositeStage(session, targets, color, plan.afterDofAltSide(),
                        plan.afterDofEncoding(), stages, !plan.hasSeparateAfterMotionBlur());
            }
        }
        if (plan.hasDeferredAfterMotionBlur() && plan.afterMotionBlurPassIndex() == passIndex) {
            final EnumSet<TranslucencyPass> stages = plan.afterMotionBlurStages();
            if (!stages.isEmpty()) {
                dispatchCompositeStage(session, targets, color, plan.afterMotionBlurAltSide(),
                        plan.afterMotionBlurEncoding(), stages, true);
            }
        }
    }

    public static void finishCompositeSession(final Object rawSession, final RenderTargets targets, final int passCount) {
        onCompositeCutpoint(rawSession, targets, passCount);
    }

    private static void dispatchCompositeStage(
            final CompositeFrameSession session,
            final RenderTargets targets,
            final RenderTarget color,
            final boolean altSide,
            final ColourEncoding encoding,
            final EnumSet<TranslucencyPass> stages,
            final boolean finalStage
    ) {
        final IrisFrameSnapshot base = session.baseSnapshot();
        final IrisSceneColor stageColor = new IrisSceneColor(
                altSide ? color.getAltTexture() : color.getMainTexture(),
                GL11C.GL_TEXTURE_2D,
                color.getWidth(),
                color.getHeight(),
                color.getInternalFormat(),
                altSide
        );
        final IrisFrameSnapshot stageSnapshot = new IrisFrameSnapshot(
                base.host(),
                base.packName(),
                base.packKind(),
                encoding,
                stageColor,
                base.sourceJitterKnown(),
                base.sourceJitterNdcX(),
                base.sourceJitterNdcY(),
                targets.getDepthTexture(),
                targets.getDepthTextureNoTranslucents(),
                base.materialSurfaces(),
                base.cameraExposure(),
                base.temporalAAParameters(),
                base.materialProjection()
        );
        session.listener().onCompositeStage(stageSnapshot, stages, finalStage);
    }

    private record CompositeFrameSession(
            FrameListener listener,
            IrisFrameSnapshot baseSnapshot,
            IrisCompositeStagePlanner.StagePlan plan
    ) { }
}
