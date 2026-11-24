package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.simulation.niagara.NiagaraCameraFrame;
import com.kltyton.lumenfx.client.render.niagara.NiagaraViewMath;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Current and previous view values consumed by Niagara GPU data interfaces. */
public final class NiagaraGpuView {
    private static final double TILE_SIZE = 2097152.0;
    public record Frame(NiagaraCameraFrame camera, Matrix4fc modelView, Matrix4fc projection,
                        Matrix4fc unjitteredProjection, Matrix4fc baseProjection, int width, int height,
                        double gameTime, double realTime, float deltaTime, float realDeltaTime, float resolutionFraction,
                        float @Nullable [] temporalAA) {
        public Frame {
            if (camera == null || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid Niagara GPU view");
            modelView = new Matrix4f(modelView); projection = new Matrix4f(projection);
            unjitteredProjection = new Matrix4f(unjitteredProjection); baseProjection = new Matrix4f(baseProjection);
            if (temporalAA != null) {
                temporalAA = temporalAA.clone();
                if (temporalAA.length != 4) throw new IllegalArgumentException("Invalid temporal AA parameter layout");
                for (float value : temporalAA) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite temporal AA parameter");
                float index = temporalAA[0], count = temporalAA[1];
                if (index != Math.floor(index) || count != Math.floor(count) || index < 0 || count < 1 || index >= count)
                    throw new IllegalArgumentException("Invalid temporal AA sample index or sequence length");
            }
        }
        @Override public float @Nullable [] temporalAA() { return temporalAA == null ? null : temporalAA.clone(); }
        public Frame(NiagaraCameraFrame camera, Matrix4fc modelView, Matrix4fc projection,
                     Matrix4fc unjitteredProjection, Matrix4fc baseProjection, int width, int height,
                     double gameTime, double realTime, float deltaTime, float realDeltaTime, float resolutionFraction) {
            this(camera, modelView, projection, unjitteredProjection, baseProjection, width, height,
                    gameTime, realTime, deltaTime, realDeltaTime, resolutionFraction, new float[] {0, 1, 0, 0});
        }
        public Frame(NiagaraCameraFrame camera, Matrix4fc modelView, Matrix4fc projection,
                     Matrix4fc unjitteredProjection, Matrix4fc baseProjection, int width, int height,
                     double gameTime, double realTime, float deltaTime, float realDeltaTime) {
            this(camera, modelView, projection, unjitteredProjection, baseProjection, width, height,
                    gameTime, realTime, deltaTime, realDeltaTime, 1);
        }
        public Frame(NiagaraCameraFrame camera, Matrix4fc modelView, Matrix4fc projection,
                     Matrix4fc unjitteredProjection, Matrix4fc baseProjection, int width, int height) {
            this(camera, modelView, projection, unjitteredProjection, baseProjection, width, height, 0, 0, 0, 0, 1);
        }
    }

    private final Frame current, previous;
    private final NiagaraSceneInputs sceneInputs;
    private final long renderFrameSequence;
    private final double[] origin, previousOrigin, viewOrigin, previousViewOrigin, tileOffset;
    private final float[] jitter, previousJitter, fov, previousFov;
    private final Matrix4f viewClip, viewClipNoAA, previousViewClip, worldClip, previousWorldClip,
            worldView, previousWorldView, screenWorld, previousScreenWorld, relativeWorldClip, previousRelativeWorldClip,
            screenRelativeWorld, clipToPreviousClip, clipToPreviousClipWithAA;
    private final float[] inverseDeviceZ;
    private final float @Nullable [] temporalSamples;

    public NiagaraGpuView(Frame current, @Nullable Frame previous, boolean zZeroToOne) {
        this(current, previous, zZeroToOne, NiagaraSceneInputs.EMPTY);
    }

    public NiagaraGpuView(Frame current, @Nullable Frame previous, boolean zZeroToOne, NiagaraSceneInputs sceneInputs) {
        this(current, previous, zZeroToOne, sceneInputs, Long.MIN_VALUE);
    }

    public NiagaraGpuView(Frame current, @Nullable Frame previous, boolean zZeroToOne, NiagaraSceneInputs sceneInputs,
                          long renderFrameSequence) {
        this.sceneInputs = java.util.Objects.requireNonNull(sceneInputs);
        this.renderFrameSequence = renderFrameSequence;
        this.current = current; this.previous = previous == null ? current : previous;
        temporalSamples = current.temporalAA();
        origin = origin(current.camera()); previousOrigin = origin(this.previous.camera());
        viewOrigin = viewOrigin(current); previousViewOrigin = viewOrigin(this.previous);
        tileOffset = new double[]{tile(origin[0]) * TILE_SIZE, tile(origin[1]) * TILE_SIZE, tile(origin[2]) * TILE_SIZE};
        jitter = jitter(current); previousJitter = jitter(this.previous);
        fov = fov(current.baseProjection()); previousFov = fov(this.previous.baseProjection());
        Matrix4f swap = new Matrix4f().identity().m11(0).m22(0).m12(1).m21(1);
        viewClip = NiagaraViewMath.canonicalViewToClipRowVector(current.projection(), 100, zZeroToOne);
        viewClipNoAA = NiagaraViewMath.canonicalViewToClipRowVector(current.unjitteredProjection(), 100, zZeroToOne);
        previousViewClip = NiagaraViewMath.canonicalViewToClipRowVector(this.previous.projection(), 100, zZeroToOne);
        Matrix4f mcFull = NiagaraViewMath.fullViewProjectionRowVectorCm(
                new Matrix4f(current.projection()).mul(current.modelView()), 100, zZeroToOne,
                NiagaraViewMath.isPerspective(current.baseProjection()));
        Matrix4f previousMcFull = NiagaraViewMath.fullViewProjectionRowVectorCm(
                new Matrix4f(this.previous.projection()).mul(this.previous.modelView()), 100, zZeroToOne,
                NiagaraViewMath.isPerspective(this.previous.baseProjection()));
        worldClip = new Matrix4f(swap).mul(mcFull);
        previousWorldClip = new Matrix4f(swap).mul(previousMcFull);
        worldView = new Matrix4f(worldClip).mul(new Matrix4f(viewClip).invert());
        previousWorldView = new Matrix4f(previousWorldClip).mul(new Matrix4f(previousViewClip).invert());
        screenWorld = NiagaraViewMath.screenToTranslatedWorld(mcFull, current.baseProjection(), 100, zZeroToOne).mul(swap);
        previousScreenWorld = NiagaraViewMath.screenToTranslatedWorld(previousMcFull, this.previous.baseProjection(), 100, zZeroToOne).mul(swap);
        double[] originHigh = {(float)origin[0], (float)origin[1], (float)origin[2]};
        relativeWorldClip = rowTranslation(originHigh[0] - origin[0], originHigh[1] - origin[1], originHigh[2] - origin[2]).mul(worldClip);
        previousRelativeWorldClip = rowTranslation(originHigh[0] - previousOrigin[0], originHigh[1] - previousOrigin[1],
                originHigh[2] - previousOrigin[2]).mul(previousWorldClip);
        screenRelativeWorld = new Matrix4f(screenWorld).mul(rowTranslation(origin[0] - originHigh[0],
                origin[1] - originHigh[1], origin[2] - originHigh[2]));
        Matrix4f currentNoAA = new Matrix4f(current.unjitteredProjection()).mul(current.modelView());
        Matrix4f previousNoAA = new Matrix4f(this.previous.unjitteredProjection()).mul(this.previous.modelView());
        clipToPreviousClip = NiagaraViewMath.clipToPrevClip(currentNoAA, previousNoAA, preTranslation(origin),
                preTranslation(previousOrigin), zZeroToOne);
        clipToPreviousClipWithAA = NiagaraViewMath.clipToPrevClip(
                new Matrix4f(current.projection()).mul(current.modelView()),
                new Matrix4f(this.previous.projection()).mul(this.previous.modelView()),
                preTranslation(origin), preTranslation(previousOrigin), zZeroToOne);
        inverseDeviceZ = NiagaraViewMath.inverseDeviceZToWorldZTransform(current.baseProjection(), 100, zZeroToOne);
    }

    public NiagaraCameraFrame camera(boolean previousFrame) {
        return (previousFrame ? previous : current).camera();
    }
    public Vector3d tileOriginCm() {
        return new Vector3d(tileOffset[0], tileOffset[1], tileOffset[2]);
    }
    public NiagaraSceneInputs sceneInputs() { return sceneInputs; }
    long renderFrameSequence() { return renderFrameSequence; }

    public void writeWorldCamera(java.nio.ByteBuffer target, boolean previousFrame) {
        double[] source = previousFrame ? previousOrigin : origin;
        target.put(high(source, 1)).putFloat(0).put(low(source, 1)).putFloat(0);
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        if (name.startsWith("View_")) name = name.substring("View_".length());
        NiagaraCameraFrame camera = current.camera();
        var atmosphere = sceneInputs.atmosphere();
        return switch (name) {
            case "ViewForward" -> floats(camera.forwardX(), camera.forwardY(), camera.forwardZ());
            case "ViewUp" -> floats(camera.upX(), camera.upY(), camera.upZ());
            case "ViewRight" -> floats(camera.rightX(), camera.rightY(), camera.rightZ());
            case "ViewSizeAndInvSize", "BufferSizeAndInvSize" -> floats(current.width(), current.height(), 1f/current.width(), 1f/current.height());
            case "BufferBilinearUVMinMax" -> floats(.5f/current.width(), .5f/current.height(),
                    1f-.5f/current.width(), 1f-.5f/current.height());
            case "ScreenPositionScaleBias" -> floats(.5f, -.5f, .5f, .5f);
            case "LightProbeSizeRatioAndInvSizeRatio" -> floats(1, 1, 1, 1);
            case "ViewRectMin" -> floats(0, 0, 0, 0);
            case "ViewRectMinAndSize" -> ints(0, 0, current.width(), current.height());
            case "NearPlane" -> floats(camera.nearPlane());
            case "SkyAtmosphereBottomRadiusKm" -> floats(atmosphere == null ? 1f : atmosphere.bottomRadiusKm());
            case "SkyAtmosphereTopRadiusKm" -> floats(atmosphere == null ? 1f : atmosphere.topRadiusKm());
            case "SkyPlanetTranslatedWorldCenterAndViewHeight" -> atmosphere == null ? floats(0, 0, 0, 0)
                    : floats(atmosphere.translatedCenterX(), atmosphere.translatedCenterY(), atmosphere.translatedCenterZ(), atmosphere.viewHeight());
            case "GameTime" -> floats((float)current.gameTime());
            case "RealTime" -> floats((float)current.realTime());
            case "PrevFrameGameTime" -> floats((float)(current.gameTime() - current.deltaTime()));
            case "PrevFrameRealTime" -> floats((float)(current.realTime() - current.realDeltaTime()));
            case "DeltaTime" -> floats(current.deltaTime());
            case "ResolutionFractionAndInv" -> floats(current.resolutionFraction(), 1f / current.resolutionFraction());
            case "FieldOfViewWideAngles" -> floats(fov);
            case "PrevFieldOfViewWideAngles" -> floats(previousFov);
            case "TanAndInvTanHalfFOV" -> tangent(current, fov);
            case "PrevTanAndInvTanHalfFOV" -> tangent(previous, previousFov);
            case "TemporalAAJitter" -> floats(jitter[0], jitter[1], previousJitter[0], previousJitter[1]);
            case "TemporalAAParams" -> {
                if (temporalSamples == null) throw new IllegalStateException("The current host has not supplied temporal AA sample parameters");
                yield floats(temporalSamples);
            }
            case "PreExposure" -> floats(sceneInputs.preExposure());
            case "OneOverPreExposure" -> floats(1f / sceneInputs.preExposure());
            case "ViewToClip" -> matrix(viewClip);
            case "ViewToClipNoAA" -> matrix(viewClipNoAA);
            case "ClipToView" -> matrix(new Matrix4f(viewClip).invert());
            case "TranslatedWorldToClip" -> matrix(worldClip);
            case "ClipToTranslatedWorld" -> matrix(new Matrix4f(worldClip).invert());
            case "TranslatedWorldToView", "TranslatedWorldToCameraView" -> matrix(worldView);
            case "ViewToTranslatedWorld", "CameraViewToTranslatedWorld" -> matrix(new Matrix4f(worldView).invert());
            case "ScreenToTranslatedWorld" -> matrix(screenWorld);
            case "PrevScreenToTranslatedWorld" -> matrix(previousScreenWorld);
            case "RelativeWorldToClip" -> matrix(relativeWorldClip);
            case "ClipToRelativeWorld" -> matrix(new Matrix4f(relativeWorldClip).invert());
            case "ScreenToRelativeWorld" -> matrix(screenRelativeWorld);
            case "PrevClipToRelativeWorld" -> matrix(new Matrix4f(previousRelativeWorldClip).invert());
            case "PrevViewToClip" -> matrix(previousViewClip);
            case "PrevClipToView" -> matrix(new Matrix4f(previousViewClip).invert());
            case "PrevTranslatedWorldToClip" -> matrix(previousWorldClip);
            case "PrevTranslatedWorldToView", "PrevTranslatedWorldToCameraView" -> matrix(previousWorldView);
            case "PrevViewToTranslatedWorld", "PrevCameraViewToTranslatedWorld" -> matrix(new Matrix4f(previousWorldView).invert());
            case "ClipToPrevClip" -> matrix(clipToPreviousClip);
            case "ClipToPrevClipWithAA" -> matrix(clipToPreviousClipWithAA);
            case "InvDeviceZToWorldZTransform" -> floats(inverseDeviceZ);
            case "ScreenToViewSpace" -> screenToView(current, fov);
            case "WorldDepthToPixelWorldRadius" -> NiagaraViewMath.isPerspective(current.baseProjection())
                    ? floats((float)Math.tan(fov[0] * .5) / current.width(), 0) : floats(0, 1);
            case "ScreenRayLengthMultiplier" -> NiagaraViewMath.isPerspective(current.baseProjection())
                    ? floats((float)Math.tan(fov[0] * .5), (float)Math.tan(fov[1] * .5), 0, 0) : floats(0, 0, 1, 1);
            case "TranslatedWorldCameraOrigin", "PrevTranslatedWorldCameraOrigin" -> floats(0, 0, 0);
            case "ViewOriginHigh" -> high(origin, 1);
            case "ViewOriginLow" -> low(origin, 1);
            case "WorldViewOriginHigh" -> high(viewOrigin, 1);
            case "WorldViewOriginLow" -> low(viewOrigin, 1);
            case "PreViewTranslationHigh" -> high(origin, -1);
            case "PreViewTranslationLow" -> low(origin, -1);
            case "PrevWorldCameraOriginHigh" -> high(previousOrigin, 1);
            case "PrevWorldCameraOriginLow" -> low(previousOrigin, 1);
            case "PrevWorldViewOriginHigh" -> high(previousViewOrigin, 1);
            case "PrevWorldViewOriginLow" -> low(previousViewOrigin, 1);
            case "PrevPreViewTranslationHigh" -> high(previousOrigin, -1);
            case "PrevPreViewTranslationLow" -> low(previousOrigin, -1);
            case "ViewTilePosition" -> floats((float)(tileOffset[0] / TILE_SIZE), (float)(tileOffset[1] / TILE_SIZE), (float)(tileOffset[2] / TILE_SIZE));
            case "RelativeWorldCameraOriginTO" -> relative(origin, 1);
            case "RelativeWorldViewOriginTO" -> relative(viewOrigin, 1);
            case "RelativePreViewTranslationTO" -> relative(origin, -1);
            case "PrevRelativeWorldCameraOriginTO" -> relative(previousOrigin, 1);
            case "PrevRelativeWorldViewOriginTO" -> relative(previousViewOrigin, 1);
            case "RelativePrevPreViewTranslationTO" -> relative(previousOrigin, -1);
            default -> null;
        };
    }

    private byte[] relative(double[] point, int sign) {
        return floats((float)(sign * (point[0] - tileOffset[0])), (float)(sign * (point[1] - tileOffset[1])),
                (float)(sign * (point[2] - tileOffset[2])));
    }

    private static double[] origin(NiagaraCameraFrame camera) { return new double[]{camera.x(), camera.y(), camera.z()}; }
    private static Vector3d preTranslation(double[] value) { return new Vector3d(-value[0] / 100, -value[2] / 100, -value[1] / 100); }
    private static Matrix4f rowTranslation(double x, double y, double z) {
        return new Matrix4f().translation((float)x, (float)y, (float)z).transpose();
    }
    private static byte[] matrix(Matrix4fc value) {
        float[] words = new float[16]; value.get(words); return floats(words);
    }
    private static byte[] screenToView(Frame frame, float[] fov) {
        boolean perspective = NiagaraViewMath.isPerspective(frame.baseProjection());
        float x = perspective ? (float)Math.tan(fov[0] * .5) : 1;
        float y = perspective ? (float)Math.tan(fov[1] * .5) : 1;
        return floats(2*x, -2*y, -x, y);
    }
    private static double[] viewOrigin(Frame frame) {
        Vector3f offset = new Matrix4f(frame.modelView()).invert().transformPosition(new Vector3f());
        NiagaraCameraFrame camera = frame.camera();
        return new double[]{camera.x() + offset.x * 100, camera.y() + offset.z * 100, camera.z() + offset.y * 100};
    }
    private static double tile(double value) { return (float)Math.floor(value / TILE_SIZE + .5); }
    private static float[] fov(Matrix4fc projection) {
        if (!NiagaraViewMath.isPerspective(projection)) return new float[]{0, 0};
        return new float[]{(float)(2 * Math.atan(1d / Math.abs(projection.m00()))),
                (float)(2 * Math.atan(1d / Math.abs(projection.m11())))};
    }
    private static float[] jitter(Frame frame) {
        Matrix4f delta = new Matrix4f(frame.projection()).mul(new Matrix4f(frame.unjitteredProjection()).invert());
        return new float[]{delta.m30(), -delta.m31()};
    }
    private static byte[] tangent(Frame frame, float[] fov) {
        if (!NiagaraViewMath.isPerspective(frame.baseProjection())) return floats(1, 1, 1, 1);
        float x = (float)Math.tan(fov[0] * .5), y = (float)Math.tan(fov[1] * .5);
        return floats(x, y, 1f / x, 1f / y);
    }
    private static byte[] high(double[] point, int sign) { return floats((float)(sign*point[0]), (float)(sign*point[1]), (float)(sign*point[2])); }
    private static byte[] low(double[] point, int sign) {
        float x = (float)(sign*point[0]), y = (float)(sign*point[1]), z = (float)(sign*point[2]);
        return floats((float)(sign*point[0]-x), (float)(sign*point[1]-y), (float)(sign*point[2]-z));
    }
    private static byte[] floats(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }
    private static byte[] ints(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }
}
