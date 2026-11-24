package com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain;

import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs.Bounds;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs.Grid;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs.Landscape;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** Maps the loaded host world's collision surfaces into a Landscape collision grid. */
public final class MinecraftLandscape implements NiagaraLandscapeInputs.CollisionGrid {
    private static final double TILE = 2097152.0;
    private record Region(int x, int z, int width, int height, boolean heights, boolean materials, List<String> remap) { }
    private record Cached(Region region, float @Nullable [] heights, byte @Nullable [] materials, Grid grid) { }
    private final ClientLevel level;
    private @Nullable Cached cached;

    public MinecraftLandscape(ClientLevel level) { this.level = level; }
    public boolean belongsTo(ClientLevel other) { return level == other; }

    public NiagaraLandscapeInputs inputs() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != level) return NiagaraLandscapeInputs.EMPTY;
        var area = minecraft.levelRenderer.viewArea();
        if (area == null || area.getCameraSectionPos() == null) return NiagaraLandscapeInputs.EMPTY;
        var center = area.getCameraSectionPos();
        int range = area.getViewDistance();
        var bounds = new Bounds((center.x() - range) * 1600.0 + 50, (center.z() - range) * 1600.0 + 50,
                (center.x() + range + 1) * 1600.0 - 50, (center.z() + range + 1) * 1600.0 - 50);
        return new NiagaraLandscapeInputs(List.of(new Landscape("minecraft:loaded_world", bounds, List.of(), this)));
    }

    @Override public @Nullable Grid capture(Bounds bounds, List<String> physicalMaterials, boolean heights, boolean materials) {
        RenderSystem.assertOnRenderThread();
        int x = (int)Math.floor(bounds.minX() * .01 - .5);
        int z = (int)Math.floor(bounds.minY() * .01 - .5);
        int lastX = (int)Math.ceil(bounds.maxX() * .01 - .5);
        int lastZ = (int)Math.ceil(bounds.maxY() * .01 - .5);
        int width = Math.addExact(Math.subtractExact(lastX, x), 1);
        int height = Math.addExact(Math.subtractExact(lastZ, z), 1);
        int limit = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
        if (width > limit || height > limit)
            throw new IllegalArgumentException("Niagara landscape collision region exceeds the device texture limit");
        var region = new Region(x, z, width, height, heights, materials, materials ? List.copyOf(physicalMaterials) : List.of());
        int count = Math.multiplyExact(width, height);
        var heightValues = heights ? new float[count] : null;
        var materialValues = materials ? new byte[count] : null;
        var cursor = new BlockPos.MutableBlockPos();
        for (int row = 0; row < height; row++) {
            int wz = z + row;
            int chunkZ = Math.floorDiv(wz, 16);
            for (int column = 0; column < width; ) {
                int wx = x + column;
                int chunkX = Math.floorDiv(wx, 16);
                ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) { cached = null; return null; }
                int end = Math.min(width, (chunkX + 1) * 16 - x);
                for (; column < end; column++) {
                    wx = x + column;
                    int cell = row * width + column;
                    if (!sample(chunk, cursor, wx, wz, cell, heightValues, materialValues, physicalMaterials)) {
                        cached = null;
                        return null;
                    }
                }
            }
        }
        var previous = cached;
        if (previous != null && previous.region().equals(region) && Arrays.equals(previous.heights(), heightValues)
                && Arrays.equals(previous.materials(), materialValues)) return previous.grid();
        double originX = x * 100.0 + 50, originY = z * 100.0 + 50;
        var tile = new Vec3(Math.floor(originX / TILE + .5), Math.floor(originY / TILE + .5), 0);
        var toWorld = new Matrix4f().translation((float)(originX - tile.x * TILE), (float)(originY - tile.y * TILE), 0).scale(100);
        var result = new Grid(width, height, tile, new Matrix4f(toWorld).invert(), toWorld,
                1f / width, 1f / height, .5f / width, .5f / height, 100, 100, heightValues, materialValues);
        cached = new Cached(region, heightValues, materialValues, result);
        return result;
    }

    private boolean sample(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int x, int z, int index,
                           float @Nullable [] heights, byte @Nullable [] materials, List<String> physicalMaterials) {
        var kind = Heightmap.Types.MOTION_BLOCKING;
        if (!chunk.hasPrimedHeightmap(kind)) kind = Heightmap.Types.WORLD_SURFACE;
        int top = chunk.hasPrimedHeightmap(kind) ? chunk.getHeight(kind, x, z) : chunk.getMaxY();
        for (int y = top; y >= chunk.getMinY(); y--) {
            cursor.set(x, y, z);
            var state = chunk.getBlockState(cursor);
            var shape = state.getCollisionShape(level, cursor);
            if (shape.isEmpty()) continue;
            double surface = shape.max(Direction.Axis.Y, .5, .5);
            if (!Double.isFinite(surface)) continue;
            if (heights != null) heights[index] = (float)((y + surface) * 100.0);
            if (materials != null) {
                String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                int found = 0;
                while (found < physicalMaterials.size() && !SourceNames.equal(physicalMaterials.get(found), block)) found++;
                materials[index] = (byte)(found >= physicalMaterials.size() || found >= 255 ? 255 : found);
            }
            return true;
        }
        return false;
    }
}
