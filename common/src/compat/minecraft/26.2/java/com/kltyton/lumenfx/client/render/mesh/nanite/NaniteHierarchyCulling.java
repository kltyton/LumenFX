package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.depth.HierarchicalSceneDepth;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/** Selects resident clusters from the same current GPU scene transforms used by mesh particles. */
final class NaniteHierarchyCulling implements AutoCloseable {
    private static final int WORKGROUP = 64;
    private static final int VIEW_BYTES = 272;
    private static final int HZB_VIEW_BYTES = 96;
    private static final int COUNTERS_BYTES = 16;

    record Instances(GpuBufferSlice transforms, int capacity, @Nullable GpuBufferSlice indices, int indexOffset,
                     @Nullable GpuBufferSlice counts, int countOffset) {
        Instances {
            if (capacity < 1 || indexOffset < 0 || countOffset < 0
                    || transforms.length() < (long)capacity * 9 * 16
                    || indices != null && indices.length() < ((long)indexOffset + capacity) * Integer.BYTES
                    || counts != null && counts.length() < ((long)countOffset + 1) * Integer.BYTES)
                throw new IllegalArgumentException("Nanite instance buffer layout disagrees");
        }
    }

    record View(Matrix4fc translatedWorldToClip, Vector3fc origin, Vector3fc forward, float nearPlane, float lodScale,
                float hardwareLodScale, boolean orthographic, boolean zeroToOne, boolean hardwareOnly, boolean frustum, boolean clampNear) {
        View {
            translatedWorldToClip = new Matrix4f(translatedWorldToClip);
            origin = new Vector3f(origin);
            forward = new Vector3f(forward);
            if (!Float.isFinite(nearPlane) || nearPlane <= 0 || !Float.isFinite(lodScale) || !Float.isFinite(hardwareLodScale))
                throw new IllegalArgumentException("Invalid Nanite view projection");
        }
    }

    record PrimitiveBounds(float maximumWpoExtent, @Nullable Matrix4fc firstPerson, @Nullable Matrix4fc previousFirstPerson) { }

    record Output(GpuBufferSlice clusters, GpuBufferSlice counters, int clusterCapacity) { }

    record OcclusionView(HierarchicalSceneDepth.View hzb, Matrix4fc translatedWorldToClip,
                         int width, int height, boolean topDown, boolean previousInstances) {
        OcclusionView {
            translatedWorldToClip = new Matrix4f(translatedWorldToClip);
            if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid Nanite occlusion viewport");
        }
    }

    record MainPass(Output visible, GpuBufferSlice deferred, int deferredCapacity) { }

    private record Stage(ComputeKernel kernel, NiagaraGpuComputeStage dispatch) implements AutoCloseable {
        @Override public void close() { kernel.close(); }
    }

    private final ComputeRuntime runtime;
    private final Stage noOcclusion, main, post;
    private final GpuBuffer empty;

    NaniteHierarchyCulling(ComputeRuntime runtime, PreparedNiagaraNaniteShaders source) {
        RenderSystem.assertOnRenderThread();
        this.runtime = runtime;
        noOcclusion = stage(source.hierarchyCull());
        Stage mainStage = null, postStage = null;
        GpuBuffer buffer = null;
        try {
            mainStage = stage(source.hierarchyMain());
            postStage = stage(source.hierarchyPost());
            buffer = RenderSystem.getDevice().createBuffer(() -> "LumenFX Nanite unused input", ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), 16);
            runtime.clearBuffer(buffer.slice(), 0);
            main = mainStage;
            post = postStage;
            empty = buffer;
        } catch (RuntimeException | Error failure) {
            if (buffer != null) buffer.close();
            if (postStage != null) postStage.close();
            if (mainStage != null) mainStage.close();
            noOcclusion.close();
            throw failure;
        }
    }

    Output cull(CommandEncoder encoder, NaniteGpuPages pages, Instances instances, View view, PrimitiveBounds bounds,
                int clusterCapacity, NaniteGpuStreamingFeedback.@Nullable Output feedback) {
        return dispatch(encoder, noOcclusion, pages, instances, view, bounds, clusterCapacity, feedback,
                null, null, null, 0);
    }

    MainPass cullMain(CommandEncoder encoder, NaniteGpuPages pages, Instances instances, View view,
                      OcclusionView previous, PrimitiveBounds bounds, int clusterCapacity,
                      NaniteGpuStreamingFeedback.@Nullable Output feedback, int deferredCapacity) {
        RenderSystem.assertOnRenderThread();
        if (deferredCapacity < 1) throw new IllegalArgumentException("Invalid Nanite deferred capacity");
        long alignment = Math.max(16, runtime.storageBufferAlignment());
        var deferred = allocate(encoder, (long)deferredCapacity * 16, alignment, 0);
        var visible = dispatch(encoder, main, pages, instances, view, bounds, clusterCapacity, feedback,
                previous, deferred, null, deferredCapacity);
        return new MainPass(visible, deferred, deferredCapacity);
    }

    Output cullPost(CommandEncoder encoder, NaniteGpuPages pages, Instances instances, View view,
                    OcclusionView current, MainPass candidates, PrimitiveBounds bounds, int clusterCapacity,
                    NaniteGpuStreamingFeedback.@Nullable Output feedback) {
        return dispatch(encoder, post, pages, instances, view, bounds, clusterCapacity, feedback,
                current, candidates.deferred(), candidates.visible().counters(), candidates.deferredCapacity());
    }

    private Output dispatch(CommandEncoder encoder, Stage stage, NaniteGpuPages pages, Instances instances, View view,
                            PrimitiveBounds bounds, int clusterCapacity, NaniteGpuStreamingFeedback.@Nullable Output feedback,
                            @Nullable OcclusionView occlusion,
                            @Nullable GpuBufferSlice deferred, @Nullable GpuBufferSlice mainCounts, int deferredCapacity) {
        RenderSystem.assertOnRenderThread();
        if (clusterCapacity < 1 || clusterCapacity >= (1 << 24))
            throw new IllegalArgumentException("Invalid Nanite culling output capacity");
        int blocks = Math.ceilDiv(mainCounts == null ? instances.capacity() : deferredCapacity, WORKGROUP);
        int x = Math.min(blocks, runtime.maxWorkGroups(0));
        int y = Math.ceilDiv(blocks, x);
        if (y > runtime.maxWorkGroups(1)) throw new IllegalArgumentException("Nanite instance count exceeds dispatch capacity");
        int width = Math.multiplyExact(x, WORKGROUP);
        long alignment = Math.max(16, runtime.storageBufferAlignment());
        var clusters = allocate(encoder, (long)clusterCapacity * 16, alignment, 0);
        var counters = allocate(encoder, COUNTERS_BYTES, alignment, GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
        runtime.clearBuffer(counters, 0);

        var bindings = ComputeBindings.builder();
        for (ShaderBinding binding : stage.dispatch().shader().bindings()) {
            if (binding instanceof ShaderBinding.SampledTexture sampled && sampled.name().equals("NaniteFurthestDepth")) {
                if (occlusion == null) throw new IllegalArgumentException("Missing Nanite occlusion view");
                bindings.sampledImage(sampled.name(), occlusion.hzb().depth());
                continue;
            }
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBufferSlice slice = switch (storage.name()) {
                case "NaniteHierarchy" -> pages.hierarchy().slice();
                case "NaniteRootNodes" -> pages.roots().slice();
                case "NaniteClusterPages" -> pages.pages().slice();
                case "NaniteAssemblyRows" -> pages.assembly().slice();
                case "MeshSceneTransforms" -> instances.transforms();
                case "NaniteInstanceIds" -> instances.indices() == null ? empty.slice() : instances.indices();
                case "NaniteInstanceCount" -> instances.counts() == null ? empty.slice() : instances.counts();
                case "NaniteVisibleClusters" -> clusters;
                case "NaniteStreamingRequests" -> feedback == null ? empty.slice() : feedback.data();
                case "NaniteCullCounts" -> counters;
                case "NaniteDeferred" -> deferred;
                case "NaniteMainCounts" -> mainCounts;
                default -> throw new IllegalArgumentException("Unexpected Nanite binding " + storage.name());
            };
            if (slice == null || slice.length() > runtime.maxStorageBufferBytes())
                throw new IllegalArgumentException("Invalid Nanite buffer " + storage.name());
            bindings.storage(storage.name(), slice, storage.access());
        }
        var block = ByteBuffer.allocate(VIEW_BYTES + (occlusion == null ? 0 : HZB_VIEW_BYTES)).order(ByteOrder.nativeOrder());
        putView(block, view);
        block.putInt(instances.capacity()).putInt(Math.toIntExact(pages.roots().size() / Integer.BYTES));
        block.putInt(clusterCapacity).putInt(feedback == null ? 0 : feedback.capacity()).putInt(pages.streamingSlots());
        block.putInt(instances.indices() == null ? -1 : instances.indexOffset());
        block.putInt(instances.counts() == null ? -1 : instances.countOffset()).putInt(width);
        block.putInt((view.hardwareOnly() ? 1 : 0) | (view.frustum() ? 0 : 2) | (view.clampNear() ? 4 : 0))
                .putFloat(bounds.maximumWpoExtent())
                .putInt(bounds.firstPerson() != null ? 1 : 0).putInt(bounds.previousFirstPerson() != null ? 1 : 0);
        var origin = view.origin();
        block.putFloat(origin.x()).putFloat(origin.y()).putFloat(origin.z()).putFloat(0);
        putFirstPerson(block, bounds.firstPerson());
        putFirstPerson(block, bounds.previousFirstPerson());
        block.putInt(feedback == null ? 0 : feedback.resourceId())
                .putInt(feedback == null ? 0 : feedback.priorityCategory()).putInt(0).putInt(0);
        if (occlusion != null) putOcclusionView(block, occlusion, deferredCapacity);
        stage.dispatch().dispatch(encoder, bindings,
                name -> uniform(block.array(), name), width, y, 1);
        return new Output(clusters, counters, clusterCapacity);
    }

    private static @Nullable byte[] uniform(byte[] block, String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        return switch (name) {
            case "NaniteTranslatedWorldToClip" -> Arrays.copyOfRange(block, 0, 64);
            case "NaniteViewForwardNear" -> Arrays.copyOfRange(block, 64, 80);
            case "NaniteLodScales" -> Arrays.copyOfRange(block, 80, 96);
            case "NaniteCullLimits" -> Arrays.copyOfRange(block, 96, 112);
            case "NanitePageLayout" -> Arrays.copyOfRange(block, 112, 128);
            case "NaniteCullFlags" -> Arrays.copyOfRange(block, 128, 144);
            case "NaniteCullViewOrigin" -> Arrays.copyOfRange(block, 144, 160);
            case "NaniteFirstPerson" -> Arrays.copyOfRange(block, 160, 208);
            case "NanitePreviousFirstPerson" -> Arrays.copyOfRange(block, 208, 256);
            case "NaniteFeedback" -> Arrays.copyOfRange(block, 256, 272);
            case "NaniteHzbWorldToClip" -> Arrays.copyOfRange(block, 272, 336);
            case "NaniteHzbViewport" -> Arrays.copyOfRange(block, 336, 352);
            case "NaniteDeferredLayout" -> Arrays.copyOfRange(block, 352, 368);
            default -> null;
        };
    }

    private Stage stage(PreparedNiagaraShaders.Stage source) {
        var shader = source.shader();
        if (shader.workgroupX() != WORKGROUP || shader.workgroupY() != 1 || shader.workgroupZ() != 1)
            throw new IllegalArgumentException("Invalid Nanite hierarchy culling workgroup");
        var kernel = runtime.createKernel(shader);
        try { return new Stage(kernel, new NiagaraGpuComputeStage(source, kernel)); }
        catch (RuntimeException | Error failure) {
            kernel.close();
            throw failure;
        }
    }

    private static void putView(ByteBuffer block, View view) {
        putMatrix(block, view.translatedWorldToClip());
        var forward = view.forward();
        block.putFloat(forward.x()).putFloat(forward.y()).putFloat(forward.z()).putFloat(view.nearPlane());
        block.putFloat(view.lodScale()).putFloat(view.hardwareLodScale()).putFloat(view.orthographic() ? 1 : 0).putFloat(view.zeroToOne() ? 1 : 0);
    }

    private static void putOcclusionView(ByteBuffer block, OcclusionView view, int deferredCapacity) {
        putMatrix(block, view.translatedWorldToClip());
        var depth = view.hzb().depth();
        int mip = depth.mipLevels() - 1;
        block.putInt(view.width()).putInt(view.height()).putInt(mip).putInt(view.topDown() ? 1 : 0);
        block.putInt(deferredCapacity).putInt(view.previousInstances() ? 1 : 0).putInt(0).putInt(0);
    }

    private static void putFirstPerson(ByteBuffer block, @Nullable Matrix4fc matrix) {
        if (matrix == null) {
            block.position(block.position() + 48);
            return;
        }
        block.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(0);
        block.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(0);
        block.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(0);
    }

    private static void putMatrix(ByteBuffer block, Matrix4fc matrix) {
        block.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(matrix.m03());
        block.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(matrix.m13());
        block.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(matrix.m23());
        block.putFloat(matrix.m30()).putFloat(matrix.m31()).putFloat(matrix.m32()).putFloat(matrix.m33());
    }

    private GpuBufferSlice allocate(CommandEncoder encoder, long bytes, long alignment, int usage) {
        if (bytes > runtime.maxStorageBufferBytes()) throw new IllegalArgumentException("Nanite culling output exceeds storage limit");
        return encoder.transientMemory().allocateGpu(bytes, alignment, ComputeUsage.storageBuffer(usage));
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        try { empty.close(); }
        finally {
            try { post.close(); }
            finally {
                try { main.close(); }
                finally { noOcclusion.close(); }
            }
        }
    }
}
