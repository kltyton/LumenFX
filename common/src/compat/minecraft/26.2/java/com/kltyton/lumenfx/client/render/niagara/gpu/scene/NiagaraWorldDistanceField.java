package com.kltyton.lumenfx.client.render.niagara.gpu.scene;

import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGlobalDistanceField;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGlobalDistanceField.Clipmap;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGlobalDistanceField.Float4;
import com.kltyton.lumenfx.client.render.niagara.gpu.scene.NiagaraVoxelDistanceFieldAtlas.Atlas;
import com.kltyton.lumenfx.client.render.niagara.gpu.scene.NiagaraVoxelDistanceFieldAtlas.Layout;
import com.kltyton.lumenfx.client.render.niagara.gpu.scene.NiagaraWorldCollisionGeometry.Cell;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraCameraFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshWorlds.SceneField;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Publishes a sparse GPU distance field from block collision shapes and registered mesh fields. */
public final class NiagaraWorldDistanceField implements AutoCloseable {
    private static final int NEW_SECTIONS_PER_FRAME = 24;

    private record Request(Layout layout, long revision, List<SceneField> sceneFields) { }
    private record SectionGeometry(LevelChunkSection source, List<AABB> boxes) { }
    private static final class Capture {
        final Request request;
        final List<Cell> cells;
        final List<AABB> boxes = new ArrayList<>();
        final HashSet<Long> seen = new HashSet<>();
        int next;

        Capture(Request request, List<Cell> cells) {
            this.request = request;
            this.cells = cells;
        }
    }
    private record BuiltField(Atlas atlas, byte[] mip) { }
    private record Job(AtomicBoolean cancelled, CompletableFuture<BuiltField> future) { }
    private record Images(Layout layout, ComputeVolume distance, ComputeVolume pageTable, ComputeVolume mip) implements AutoCloseable {
        @Override public void close() {
            try { mip.close(); }
            finally {
                try { pageTable.close(); }
                finally { distance.close(); }
            }
        }

        NiagaraGlobalDistanceField forView(NiagaraCameraFrame camera) {
            var clips = new ArrayList<Clipmap>(layout.clips().size());
            float maxDistance = 0;
            int mipSide = layout.mipResolution(), count = layout.clips().size(), mipDepth = mipSide * count;
            for (int index = 0; index < count; index++) {
                var clip = layout.clips().get(index);
                double x = clip.x() - camera.x(), y = clip.y() - camera.y(), z = clip.z() - camera.z();
                double inverseSize = .5 / clip.extent();
                double addX = .5 - x * inverseSize, addY = .5 - y * inverseSize, addZ = .5 - z * inverseSize;
                float minMipZ = (index * mipSide + .5f) / mipDepth;
                float maxMipZ = ((index + 1) * mipSide - .5f) / mipDepth;
                clips.add(new Clipmap(new Float4((float)x, (float)y, (float)z, (float)clip.extent()),
                        new Float4((float)addX, (float)addY, (float)addZ, (float)inverseSize),
                        new Float4((float)inverseSize, (float)inverseSize, (float)(inverseSize / count), minMipZ),
                        new Float4((float)addX, (float)addY, (float)((addZ + index) / count), maxMipZ)));
                maxDistance = Math.max(maxDistance, (float)clip.extent());
            }
            int influence = NiagaraVoxelDistanceFieldAtlas.INFLUENCE_VOXELS;
            float mipTransition = (influence + (float)layout.mipFactor() / influence) / (2 * influence);
            return new NiagaraGlobalDistanceField(distance, null, pageTable, mip, clips, layout.pages(), layout.resolution(),
                    maxDistance, layout.mipFactor(), mipTransition);
        }
    }

    private final Map<Long, SectionGeometry> sections = new HashMap<>();
    private @Nullable ClientLevel level;
    private long revision;
    private @Nullable Request requested;
    private @Nullable Capture capture;
    private @Nullable Job job;
    private @Nullable Images images;
    private long publishedView = Long.MIN_VALUE;

    public @Nullable NiagaraGlobalDistanceField prepare(ClientLevel world, NiagaraCameraFrame camera,
                                                        NiagaraPlatformEnvironment platform, int viewDistanceChunks, long viewKey) {
        RenderSystem.assertOnRenderThread();
        int clipmaps = platform.distanceFieldClipmapCount();
        if (clipmaps == 0) {
            close();
            return null;
        }
        if (level != world) {
            close();
            level = world;
        }
        if (job != null && job.future().isDone()) {
            var completed = job.future();
            job = null;
            var replacement = upload(completed.join());
            var old = images;
            images = replacement;
            if (old != null) RenderSystem.queueFencedTask(old::close);
        }
        double viewDistance = (double)viewDistanceChunks * SectionPos.SECTION_SIZE * 100;
        Layout desired = NiagaraVoxelDistanceFieldAtlas.layout(camera.x(), camera.y(), camera.z(), viewDistance,
                platform.distanceFieldResolution(), clipmaps, platform.distanceFieldExtentExponent(), platform.distanceFieldMipFactor());
        if (requested != null && !containsCamera(requested.layout(), camera)) {
            discardCapture();
            requested = null;
        }
        var sceneFields = NiagaraStaticMeshWorlds.captureScene(world);
        var next = new Request(desired, revision, sceneFields);
        if (capture == null && job == null && !next.equals(requested)) {
            var largest = desired.clips().stream().max(Comparator.comparingDouble(
                    NiagaraVoxelDistanceFieldAtlas.Clipmap::extent)).orElseThrow();
            capture = new Capture(next, NiagaraWorldCollisionGeometry.sections(world, largest));
            requested = next;
        }
        if (capture != null) captureSections(world, capture, sceneFields);
        publishedView = images != null && containsCamera(images.layout(), camera) ? viewKey : Long.MIN_VALUE;
        return cached(world, camera, viewKey);
    }

    /** Rebind the live atlas for a viewless tick only while the camera and world still match. */
    public @Nullable NiagaraGlobalDistanceField cached(ClientLevel world, NiagaraCameraFrame camera, long viewKey) {
        RenderSystem.assertOnRenderThread();
        var current = images;
        return current != null && world == level && publishedView != Long.MIN_VALUE && publishedView == viewKey
                && containsCamera(current.layout(), camera) ? current.forView(camera) : null;
    }

    public void blockChanged(ClientLevel world, BlockPos block) {
        if (level != world) return;
        for (int y = SectionPos.blockToSectionCoord(block.getY() - 1); y <= SectionPos.blockToSectionCoord(block.getY() + 1); y++)
            for (int z = SectionPos.blockToSectionCoord(block.getZ() - 1); z <= SectionPos.blockToSectionCoord(block.getZ() + 1); z++)
                for (int x = SectionPos.blockToSectionCoord(block.getX() - 1); x <= SectionPos.blockToSectionCoord(block.getX() + 1); x++)
                    sections.remove(SectionPos.asLong(x, y, z));
        revision = Math.incrementExact(revision);
    }

    public void chunkChanged(ClientLevel world, ChunkPos chunk) {
        if (level != world) return;
        sections.keySet().removeIf(key -> Math.abs(SectionPos.x(key) - chunk.x()) <= 1 && Math.abs(SectionPos.z(key) - chunk.z()) <= 1);
        revision = Math.incrementExact(revision);
    }

    private void captureSections(ClientLevel world, Capture current, List<SceneField> sceneFields) {
        int budget = NEW_SECTIONS_PER_FRAME;
        while (current.next < current.cells.size() && budget != 0) {
            Cell cell = current.cells.get(current.next++);
            long key = cell.key();
            var geometry = sections.get(key);
            if (geometry == null || geometry.source() != cell.section()) {
                geometry = new SectionGeometry(cell.section(), NiagaraWorldCollisionGeometry.capture(world, cell));
                sections.put(key, geometry);
                budget--;
            }
            current.boxes.addAll(geometry.boxes());
            current.seen.add(key);
        }
        if (current.next != current.cells.size()) return;
        sections.keySet().retainAll(current.seen);
        List<AABB> boxes = List.copyOf(current.boxes);
        Layout layout = current.request.layout();
        // A terrain capture can span several frames. The immutable mesh snapshot comes from the submission frame.
        requested = new Request(layout, current.request.revision(), sceneFields);
        var cancelled = new AtomicBoolean();
        job = new Job(cancelled, CompletableFuture.supplyAsync(() -> {
            var volumes = new ArrayList<NiagaraMeshDistanceVolume>();
            for (var field : sceneFields) for (var pose : field.instances()) {
                NiagaraVoxelDistanceFieldAtlas.checkCancellation(cancelled::get);
                var volume = NiagaraMeshDistanceVolume.create(pose, field.field());
                if (volume != null) volumes.add(volume);
            }
            var atlas = NiagaraVoxelDistanceFieldAtlas.compose(layout, boxes, volumes, cancelled::get);
            return new BuiltField(atlas, NiagaraDistanceFieldMip.compose(atlas, cancelled::get));
        }, Util.backgroundExecutor().forName("LumenFX global distance field")));
        capture = null;
    }

    private static boolean containsCamera(Layout layout, NiagaraCameraFrame camera) {
        for (var clip : layout.clips())
            if (Math.abs(camera.x() - clip.x()) < clip.extent() && Math.abs(camera.y() - clip.y()) < clip.extent()
                    && Math.abs(camera.z() - clip.z()) < clip.extent()) return true;
        return false;
    }

    private static Images upload(BuiltField built) {
        Atlas atlas = built.atlas();
        int side = atlas.layout().pages();
        int size = NiagaraVoxelDistanceFieldAtlas.ATLAS_SIZE;
        var distance = uploadR8("Niagara.WorldDistanceAtlas", size, size, atlas.depth(), atlas.distance(),
                NiagaraGlobalDistanceField.ATLAS_SAMPLING);
        try {
            var pages = MemoryUtil.memAlloc(Math.multiplyExact(atlas.pageTable().length, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
            ComputeVolume table;
            try {
                pages.asIntBuffer().put(atlas.pageTable());
                table = ComputeVolume.createSampled("Niagara.WorldDistancePageTable", side, side,
                        Math.multiplyExact(side, atlas.layout().clips().size()), List.of(pages),
                        new NativeTextureStorage(TextureStorageFormat.R32_UINT, false), NiagaraGlobalDistanceField.PAGE_TABLE_SAMPLING);
            } finally {
                MemoryUtil.memFree(pages);
            }
            try {
                int mipSide = atlas.layout().mipResolution();
                var mip = uploadR8("Niagara.WorldDistanceMip", mipSide, mipSide,
                        Math.multiplyExact(mipSide, atlas.layout().clips().size()), built.mip(), NiagaraGlobalDistanceField.MIP_SAMPLING);
                return new Images(atlas.layout(), distance, table, mip);
            } catch (RuntimeException | Error failure) {
                table.close();
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            distance.close();
            throw failure;
        }
    }

    private static ComputeVolume uploadR8(String label, int width, int height, int depth, byte[] values, ComputeVolume.Sampling sampling) {
        var pixels = MemoryUtil.memAlloc(values.length);
        try {
            pixels.put(values).flip();
            return ComputeVolume.createSampled(label, width, height, depth, List.of(pixels),
                    new NativeTextureStorage(TextureStorageFormat.G8, false), sampling);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private void discardCapture() {
        if (job != null) {
            job.cancelled().set(true);
            job.future().cancel(false);
            job = null;
        }
        capture = null;
    }

    @Override public void close() {
        discardCapture();
        var old = images;
        images = null;
        publishedView = Long.MIN_VALUE;
        sections.clear();
        requested = null;
        level = null;
        revision = 0;
        if (old != null) RenderSystem.queueFencedTask(old::close);
    }
}
