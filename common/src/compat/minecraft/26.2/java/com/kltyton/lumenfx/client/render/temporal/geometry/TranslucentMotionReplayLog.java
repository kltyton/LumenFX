package com.kltyton.lumenfx.client.render.temporal.geometry;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.VulkanEnabledFeatures;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderLayout;
import com.kltyton.lumenfx.effect.render.MaterialDepth;
import com.lowdragmc.photon.client.render.ParticleRasterPass;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

/**
 * Tile-partitioned, overflow-proof per-pixel ordered fragment replay log for depth-writing translucent
 * velocity and active translucent depth-sync passes.
 *
 * <p>Requires OpenGL 4.3+ (for GLSL {@code #version 430} SSBOs) or Vulkan with BOTH
 * {@code fragmentStoresAndAtomics} and {@code geometryShader} (for {@code gl_PrimitiveID}).
 * Devices outside those capabilities use {@link TranslucentMotionStepReplay} ({@code #version 330}).
 *
 * <p>Overflow guarantee: the head table is sized to the active tile (not full 4K/8K screen), leaving
 * &gt;2M nodes per tile inside the 128 MiB budget. On OpenGL 4.3+, tiles whose worst-case fragment count
 * could exceed capacity read back the 4-byte {@code lfxMotionLogCounter} and, on overflow, refuse to resolve
 * and recursively split the tile (down to {@code 1x1}, then falling back to per-primitive stepping).
 * On Vulkan (no mid-frame {@code submit()}), tiles are statically partitioned so {@code tileArea * maxPrimitives <= capacity},
 * or routed directly to {@link TranslucentMotionStepReplay} when {@code maxPrimitives > capacity}.
 */
public final class TranslucentMotionReplayLog implements AutoCloseable {
    private static final String CLEAR_UNIFORM = "LfxMotionLogClear";
    private static final int CLEAR_UNIFORM_BYTES = 32;
    private static final int DRAW_UNIFORM_BYTES = 16;
    private static final int MIN_NODES = 262_144;
    public static final int MAX_TILE_PIXELS = 512 * 1024;
    public static final long MAX_STORAGE_BYTES = 128L * 1024L * 1024L;
    public static final int LOCAL_SORT_CAP = 32;

    public record TileRect(int x, int y, int width, int height) {
        public long area() { return (long) width * (long) height; }

        public boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }

        public TileRect[] split() {
            if (width >= height && width > 1) {
                int left = width / 2;
                return new TileRect[] {
                        new TileRect(x, y, left, height),
                        new TileRect(x + left, y, width - left, height)
                };
            }
            if (height > 1) {
                int bottom = height / 2;
                return new TileRect[] {
                        new TileRect(x, y, width, bottom),
                        new TileRect(x, y + bottom, width, height - bottom)
                };
            }
            return new TileRect[] { this };
        }

        public ScissorState toScissor() {
            var state = new ScissorState();
            state.enable(x, y, width, height);
            return state;
        }

        public static @Nullable TileRect clamp(@Nullable ScissorState scissor, int viewportWidth, int viewportHeight) {
            if (viewportWidth <= 0 || viewportHeight <= 0) return null;
            if (scissor == null || !scissor.enabled()) {
                return new TileRect(0, 0, viewportWidth, viewportHeight);
            }
            int x0 = Math.max(0, scissor.x());
            int y0 = Math.max(0, scissor.y());
            int x1 = Math.min(viewportWidth, scissor.x() + scissor.width());
            int y1 = Math.min(viewportHeight, scissor.y() + scissor.height());
            if (x1 <= x0 || y1 <= y0) return null;
            return new TileRect(x0, y0, x1 - x0, y1 - y0);
        }
    }

    public record DrawSpec(MaterialDepth.Compare compare, boolean writesColorDepth,
                           boolean batchWritesVelocity, boolean velocityFromDepth) {
        public int packedFlags() {
            return TranslucentMotionLogLayout.packDrawFlags(compare, writesColorDepth, batchWritesVelocity, velocityFromDepth);
        }
    }

    public static final class DrawCursor {
        private final GpuBufferSlice logBuffer;
        private final GpuBuffer drawUniformBuffer;
        private final int stride;
        private final int drawCount;
        private int nextIndex;

        DrawCursor(GpuBufferSlice logBuffer, GpuBuffer drawUniformBuffer, int stride, int drawCount) {
            this.logBuffer = logBuffer;
            this.drawUniformBuffer = drawUniformBuffer;
            this.stride = stride;
            this.drawCount = drawCount;
        }

        public GpuBufferSlice logBuffer() { return logBuffer; }

        public void reset() { nextIndex = 0; }

        public void bindNext(RenderPass pass) {
            if (nextIndex >= drawCount) {
                throw new IllegalStateException("Translucent motion log draw cursor exhausted (" + drawCount + ")");
            }
            long offset = (long) nextIndex * stride;
            nextIndex++;
            pass.setUniform(TranslucentMotionLogLayout.DRAW_UNIFORM,
                    drawUniformBuffer.slice(offset, DRAW_UNIFORM_BYTES));
        }

        public void verifyConsumed() {
            if (nextIndex != drawCount) {
                throw new IllegalStateException("Translucent motion log draw count mismatch: expected "
                        + drawCount + ", consumed " + nextIndex);
            }
        }
    }

    private @Nullable GpuBuffer storage;
    private int storageCapacityNodes;
    private int storageTileMaxPixels;
    private int storageByteSize;
    private @Nullable GpuBuffer clearUniformBuffer;
    private @Nullable GpuBuffer drawUniformBuffer;
    private int drawUniformCapacityBytes;
    private @Nullable RenderPipeline clearPipeline;
    private @Nullable RenderPipeline resolvePipeline;

    /** Checks actual runtime backend capabilities for the GLSL 430 / Vulkan SSBO + {@code gl_PrimitiveID} log path. */
    public static boolean supportsHardwareLog(GpuDevice device) {
        return switch (device.backend) {
            case GlDevice ignored -> GL.getCapabilities().OpenGL43;
            case VulkanDevice vulkan -> supportsVulkanHardwareLog(vulkan);
            default -> false;
        };
    }

    private static boolean supportsVulkanHardwareLog(VulkanDevice vulkan) {
        if (VulkanEnabledFeatures.isRecorded()) {
            return VulkanEnabledFeatures.fragmentStoresAndAtomics() && VulkanEnabledFeatures.geometryShader();
        }
        try (var stack = MemoryStack.stackPush()) {
            var supported = VkPhysicalDeviceFeatures.calloc(stack);
            VK12.vkGetPhysicalDeviceFeatures(vulkan.vkDevice().getPhysicalDevice(), supported);
            return supported.fragmentStoresAndAtomics() && supported.geometryShader();
        }
    }

    /** Pure capability helper for unit-testing Vulkan and OpenGL capability gates without a live GPU context. */
    public static boolean supportsHardwareLogForFeatures(boolean isVulkan, boolean glOpenGL43,
                                                         boolean vkFragmentStoresAndAtomics,
                                                         boolean vkGeometryShader) {
        return isVulkan ? (vkFragmentStoresAndAtomics && vkGeometryShader) : glOpenGL43;
    }

    /** Computes node capacity strictly bounded within {@code maxBytes} for a tile head table of {@code tilePixels}. */
    public static int computeNodeCapacity(int tilePixels, long maxBytes) {
        long headerAndHeadBytes = (TranslucentMotionLogLayout.HEADER_UINTS + (long) tilePixels) * Integer.BYTES;
        long nodeBytes = (long) TranslucentMotionLogLayout.NODE_UINTS * Integer.BYTES;
        long available = Math.max(0L, maxBytes - headerAndHeadBytes);
        return (int) Math.min(Integer.MAX_VALUE / TranslucentMotionLogLayout.NODE_UINTS, available / nodeBytes);
    }

    /**
     * Partitions {@code region} into disjoint tiles whose area fits both {@code maxTilePixels} and the
     * per-tile fragment budget ({@code nodeCapacity / maxPrimitives} for static Vulkan tiling, or
     * {@code max(1, nodeCapacity / 4)} for dynamic OpenGL overflow-checked tiling).
     */
    public static List<TileRect> planInitialTiles(TileRect region, long maxPrimitives,
                                                  int maxTilePixels, int nodeCapacity,
                                                  boolean dynamicReadback) {
        long clampedPrims = Math.max(1L, maxPrimitives);
        long maxArea = Math.min(maxTilePixels,
                dynamicReadback ? Math.max(1L, nodeCapacity / 4L) : Math.max(1L, nodeCapacity / clampedPrims));
        var tiles = new ArrayList<TileRect>();
        var stack = new ArrayDeque<TileRect>();
        stack.push(region);
        while (!stack.isEmpty()) {
            TileRect current = stack.pop();
            if (current.area() <= maxArea || (current.width() == 1 && current.height() == 1)) {
                tiles.add(current);
            } else {
                TileRect[] halves = current.split();
                stack.push(halves[1]);
                stack.push(halves[0]);
            }
        }
        return tiles;
    }

    /**
     * Executes tiled, overflow-proof capture & resolve across {@code scissor} (or full viewport).
     *
     * @return {@code true} if handled completely; {@code false} if Vulkan cannot statically fit
     *         {@code maxPrimitives <= nodeCapacity} and caller should execute {@link TranslucentMotionStepReplay}.
     */
    public boolean executeTiled(CommandEncoder encoder,
                                GeometryMotionRenderer.CoverageTargets targets,
                                GpuTextureView motionDepth,
                                GpuTextureView preColorView,
                                GpuTextureView existingMotionView,
                                @Nullable ScissorState scissor,
                                List<DrawSpec> draws,
                                long maxPrimitives,
                                BiConsumer<RenderPass, DrawCursor> captureDrawer,
                                Consumer<ScissorState> singlePixelOverflowFallback) {
        RenderSystem.assertOnRenderThread();
        if (draws.isEmpty()) return true;
        int width = targets.width();
        int height = targets.height();
        TileRect region = TileRect.clamp(scissor, width, height);
        if (region == null) return true;

        int tilePixelCap = (int) Math.min((long) MAX_TILE_PIXELS, region.area());
        ensureStorage(tilePixelCap);
        boolean glBackend = storage instanceof GlBuffer;
        if (!glBackend && maxPrimitives > storageCapacityNodes) {
            return false;
        }

        int alignment = Math.max(DRAW_UNIFORM_BYTES,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());
        int stride = align(DRAW_UNIFORM_BYTES, alignment);
        int drawBytes = Math.multiplyExact(draws.size(), stride);
        ensureDrawUniformBuffer(drawBytes);
        ensureClearUniformBuffer();

        ByteBuffer drawData = ByteBuffer.allocateDirect(drawBytes).order(ByteOrder.nativeOrder());
        for (int i = 0, n = draws.size(); i < n; i++) {
            int base = i * stride;
            drawData.putInt(base, i);
            drawData.putInt(base + 4, draws.get(i).packedFlags());
            drawData.putInt(base + 8, 0);
            drawData.putInt(base + 12, 0);
        }
        drawData.position(0).limit(drawBytes);
        encoder.writeToBuffer(Objects.requireNonNull(drawUniformBuffer).slice(0, drawBytes), drawData);

        GpuBufferSlice logRange = Objects.requireNonNull(storage).slice(0, storageByteSize);
        var cursor = new DrawCursor(logRange, drawUniformBuffer, stride, draws.size());
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);

        List<TileRect> initialTiles = planInitialTiles(region, maxPrimitives, storageTileMaxPixels,
                storageCapacityNodes, glBackend);
        var queue = new ArrayDeque<TileRect>(initialTiles.size());
        for (int i = initialTiles.size() - 1; i >= 0; i--) {
            queue.push(initialTiles.get(i));
        }

        GlBinding glBinding = null;
        if (storage instanceof GlBuffer gl) {
            glBinding = GlBinding.capture();
            GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, TranslucentMotionLogLayout.GL_SLOT,
                    gl.handle(), 0L, logRange.length());
        }
        try {
            while (!queue.isEmpty()) {
                TileRect tile = queue.pop();
                ScissorState tileScissor = tile.toScissor();
                int tileCapacity = computeNodeCapacity((int) tile.area(), storageByteSize);
                if (tileCapacity <= 0) {
                    if (tile.width() > 1 || tile.height() > 1) {
                        TileRect[] halves = tile.split();
                        queue.push(halves[1]);
                        queue.push(halves[0]);
                        continue;
                    }
                    if (glBinding != null) { glBinding.restore(); glBinding = null; }
                    singlePixelOverflowFallback.accept(tileScissor);
                    if (storage instanceof GlBuffer gl) {
                        glBinding = GlBinding.capture();
                        GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, TranslucentMotionLogLayout.GL_SLOT,
                                gl.handle(), 0L, logRange.length());
                    }
                    continue;
                }

                writeClearUniform(encoder, tile, tileCapacity);
                var clearDescriptor = RenderPassDescriptor.create(() -> "LumenFX translucent motion log clear")
                        .withColorAttachment(targets.previousNdc())
                        .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
                try (var pass = ParticleRasterPass.open(encoder, clearDescriptor, tileScissor)) {
                    pass.setPipeline(clearPipeline());
                    pass.setUniform(CLEAR_UNIFORM, Objects.requireNonNull(clearUniformBuffer).slice(0, CLEAR_UNIFORM_BYTES));
                    pass.setUniform(TranslucentMotionLogLayout.NAME, logRange);
                    pass.draw(3, 1, 0, 0);
                }
                if (glBackend) {
                    GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
                }

                cursor.reset();
                var captureDescriptor = RenderPassDescriptor.create(() -> "LumenFX translucent motion log capture")
                        .withColorAttachment(targets.previousNdc())
                        .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
                try (var pass = ParticleRasterPass.open(encoder, captureDescriptor, tileScissor)) {
                    captureDrawer.accept(pass, cursor);
                }
                cursor.verifyConsumed();
                if (glBackend) {
                    GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
                }

                boolean canOverflow = maxPrimitives > tileCapacity / Math.max(1L, tile.area());
                if (glBackend && canOverflow) {
                    GlCaptureStatus status = readGlCaptureStatus(((GlBuffer) storage).handle());
                    if (status.isEmpty(tileCapacity)) {
                        continue;
                    }
                    if (status.isOverflowed(tileCapacity)) {
                        if (tile.width() > 1 || tile.height() > 1) {
                            TileRect[] halves = tile.split();
                            queue.push(halves[1]);
                            queue.push(halves[0]);
                            continue;
                        }
                        if (glBinding != null) { glBinding.restore(); glBinding = null; }
                        singlePixelOverflowFallback.accept(tileScissor);
                        if (storage instanceof GlBuffer gl) {
                            glBinding = GlBinding.capture();
                            GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, TranslucentMotionLogLayout.GL_SLOT,
                                    gl.handle(), 0L, logRange.length());
                        }
                        continue;
                    }
                }

                var resolveDescriptor = RenderPassDescriptor.create(() -> "LumenFX translucent motion log resolve")
                        .withColorAttachment(targets.previousNdc())
                        .withColorAttachment(targets.depthCoverage())
                        .withDepthAttachment(motionDepth)
                        .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
                try (var pass = ParticleRasterPass.open(encoder, resolveDescriptor, tileScissor)) {
                    pass.setPipeline(resolvePipeline());
                    pass.setUniform(TranslucentMotionLogLayout.NAME, logRange);
                    pass.bindTexture("PreColorDepthSampler", preColorView, sampler);
                    pass.bindTexture("ExistingMotionDepthSampler", existingMotionView, sampler);
                    pass.draw(3, 1, 0, 0);
                }
            }
            return true;
        } finally {
            if (glBinding != null) {
                glBinding.restore();
            }
        }
    }

    private void writeClearUniform(CommandEncoder encoder, TileRect tile, int tileCapacity) {
        try (var stack = MemoryStack.stackPush()) {
            ByteBuffer clearData = stack.malloc(CLEAR_UNIFORM_BYTES).order(ByteOrder.nativeOrder());
            clearData.putInt(0, tile.x());
            clearData.putInt(4, tile.y());
            clearData.putInt(8, tile.width());
            clearData.putInt(12, tile.height());
            clearData.putInt(16, tileCapacity);
            clearData.putInt(20, 0);
            clearData.putInt(24, 0);
            clearData.putInt(28, 0);
            encoder.writeToBuffer(Objects.requireNonNull(clearUniformBuffer).slice(0, CLEAR_UNIFORM_BYTES), clearData);
        }
    }

    /** Readback snapshot of SSBO header words 0 ({@code lfxMotionLogCounter}) and 6 ({@code lfxMotionLogOverflow}). */
    public record GlCaptureStatus(long counter, boolean overflowed) {
        public boolean isOverflowed(int tileCapacity) {
            return overflowed || counter > tileCapacity;
        }

        public boolean isEmpty(int tileCapacity) {
            return !isOverflowed(tileCapacity) && counter == 0L;
        }
    }

    /**
     * Exact worst-case 32-bit wraparound model of GPU {@code atomicAdd(lfxMotionLogCounter, 1u)} paired with
     * sticky monotonic {@code atomicOr(lfxMotionLogOverflow, 1u)} (assuming zero early-discard visibility so
     * every fragment increments the 32-bit counter and can wrap modulo {@code 2^32}).
     */
    public static GlCaptureStatus simulateGpuCaptureCounter(long totalFragments, int nodeCapacity) {
        if (totalFragments < 0L || nodeCapacity < 0) {
            throw new IllegalArgumentException("Invalid fragment count or capacity");
        }
        long wrappedUint32 = totalFragments & 0xFFFF_FFFFL;
        // Because capacity < 2^32, if totalFragments > nodeCapacity then at least the (nodeCapacity + 1)-th
        // atomicAdd receives lfxNodeIndex == nodeCapacity and executes atomicOr(lfxMotionLogOverflow, 1u).
        boolean stickyOverflow = totalFragments > nodeCapacity;
        return new GlCaptureStatus(wrappedUint32, stickyOverflow);
    }

    private static GlCaptureStatus readGlCaptureStatus(int bufferHandle) {
        int prev = GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
        try (var stack = MemoryStack.stackPush()) {
            IntBuffer out = stack.mallocInt(7);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, bufferHandle);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, out);
            return new GlCaptureStatus(Integer.toUnsignedLong(out.get(0)), out.get(6) != 0);
        } finally {
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, prev);
        }
    }

    private void ensureStorage(int tileMaxPixels) {
        var device = RenderSystem.getDevice();
        long maxAllocBudget = Math.min(MAX_STORAGE_BYTES,
                Math.max((long) (TranslucentMotionLogLayout.HEADER_UINTS + tileMaxPixels) * Integer.BYTES
                                + (long) MIN_NODES * TranslucentMotionLogLayout.NODE_UINTS * Integer.BYTES,
                        device.getDeviceInfo().limits().maxMemoryAllocationSize() / 8L));
        int capacityNodes = computeNodeCapacity(tileMaxPixels, maxAllocBudget);
        long totalBytesLong = ((long) TranslucentMotionLogLayout.HEADER_UINTS + tileMaxPixels
                + (long) capacityNodes * TranslucentMotionLogLayout.NODE_UINTS) * Integer.BYTES;
        int totalBytes = Math.toIntExact(totalBytesLong);

        if (storage != null && storage.size() >= totalBytes) {
            storageTileMaxPixels = tileMaxPixels;
            storageCapacityNodes = capacityNodes;
            storageByteSize = totalBytes;
            return;
        }
        if (device.backend instanceof VulkanDevice vulkan && !supportsVulkanHardwareLog(vulkan)) {
            throw new IllegalStateException("Translucent motion replay log requires Vulkan fragmentStoresAndAtomics and geometryShader");
        }
        if (storage != null) {
            storage.close();
            storage = null;
        }
        storage = device.createBuffer(() -> "LumenFX translucent motion replay log",
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST),
                totalBytes);
        storageTileMaxPixels = tileMaxPixels;
        storageCapacityNodes = capacityNodes;
        storageByteSize = totalBytes;
    }

    private void ensureClearUniformBuffer() {
        if (clearUniformBuffer != null) return;
        clearUniformBuffer = RenderSystem.getDevice().createBuffer(
                () -> "LumenFX translucent motion log clear uniform",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                CLEAR_UNIFORM_BYTES);
    }

    private void ensureDrawUniformBuffer(int bytes) {
        if (drawUniformBuffer != null && drawUniformCapacityBytes >= bytes) return;
        if (drawUniformBuffer != null) {
            drawUniformBuffer.close();
            drawUniformBuffer = null;
        }
        int alloc = Math.max(bytes, 4096);
        drawUniformBuffer = RenderSystem.getDevice().createBuffer(
                () -> "LumenFX translucent motion log draw uniforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                alloc);
        drawUniformCapacityBytes = alloc;
    }

    private RenderPipeline clearPipeline() {
        if (clearPipeline != null) return clearPipeline;
        var bindings = BindGroupLayout.builder()
                .withUniform(CLEAR_UNIFORM, UniformType.UNIFORM_BUFFER);
        TranslucentMotionLogLayout.addStorage(bindings);
        clearPipeline = RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/temporal/translucent_motion_log_clear"))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id("temporal/translucent_motion_log_clear"))
                .withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(Optional.empty())
                .withColorTargetState(0, new ColorTargetState(Optional.empty(),
                        MotionShaderLayout.MOTION_CLIP_FORMAT, 0))
                .withBindGroupLayout(bindings.build())
                .build();
        return clearPipeline;
    }

    private RenderPipeline resolvePipeline() {
        if (resolvePipeline != null) return resolvePipeline;
        var bindings = BindGroupLayout.builder()
                .withSampler("PreColorDepthSampler")
                .withSampler("ExistingMotionDepthSampler");
        TranslucentMotionLogLayout.addStorage(bindings);
        resolvePipeline = RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/temporal/translucent_motion_log_resolve"))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id("temporal/translucent_motion_log_resolve"))
                .withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .withColorTargetState(0, new ColorTargetState(Optional.empty(),
                        MotionShaderLayout.MOTION_CLIP_FORMAT, ColorTargetState.WRITE_ALL))
                .withColorTargetState(1, new ColorTargetState(Optional.empty(),
                        MotionShaderLayout.MOTION_DEPTH_FORMAT,
                        ColorTargetState.WRITE_RED | ColorTargetState.WRITE_GREEN))
                .withBindGroupLayout(bindings.build())
                .build();
        return resolvePipeline;
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    @Override public void close() {
        if (storage != null) { storage.close(); storage = null; }
        if (clearUniformBuffer != null) { clearUniformBuffer.close(); clearUniformBuffer = null; }
        if (drawUniformBuffer != null) { drawUniformBuffer.close(); drawUniformBuffer = null; }
        storageCapacityNodes = 0;
        storageTileMaxPixels = 0;
        storageByteSize = 0;
        drawUniformCapacityBytes = 0;
        clearPipeline = null;
        resolvePipeline = null;
    }

    private record GlBinding(int generic, int indexed, long start, long size) {
        static GlBinding capture() {
            int slot = TranslucentMotionLogLayout.GL_SLOT;
            try (var stack = MemoryStack.stackPush()) {
                var value = stack.mallocLong(1);
                GL32C.glGetInteger64i_v(GL43C.GL_SHADER_STORAGE_BUFFER_START, slot, value);
                long start = value.get(0);
                GL32C.glGetInteger64i_v(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, slot, value);
                return new GlBinding(
                        GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING),
                        GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, slot),
                        start,
                        value.get(0));
            }
        }

        void restore() {
            int slot = TranslucentMotionLogLayout.GL_SLOT;
            if (indexed == 0 || size == 0L) {
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, slot, indexed);
            } else {
                GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, slot, indexed, start, size);
            }
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, generic);
        }
    }

    // -------------------------------------------------------------------------
    // Pure per-pixel & tiled-overflow reference mirroring GPU shaders + planner
    // -------------------------------------------------------------------------

    public record FragmentNode(int drawIndex, int instanceId, int primitiveId,
                               float fragDepth, MaterialDepth.Compare compare,
                               boolean writesColorDepth, boolean velocityEligible,
                               float currentClipDepth,
                               float prevNdcX, float prevNdcY, float prevNdcZ, float prevNdcW) {
        public int compareCode() { return TranslucentMotionLogLayout.compareCode(compare); }
    }

    public record ScreenFragment(int x, int y, FragmentNode node) { }

    public record PixelState(float colorDepth, float motionDepth,
                             float prevNdcX, float prevNdcY, float prevNdcZ, float prevNdcW,
                             float covClipDepth, float covFragDepth) {
        public boolean hasValidVelocity() {
            return prevNdcW >= 1.0f && Float.compare(motionDepth, covFragDepth) == 0;
        }
    }

    public record TiledSimulationResult(PixelState[][] pixels, int tilesResolved, int overflowSplits,
                                        int singlePixelStepFallbacks, int droppedFragments) { }

    public static boolean depthTest(int compareCode, float fragDepth, float targetDepth) {
        return switch (compareCode) {
            case TranslucentMotionLogLayout.COMPARE_ALWAYS -> true;
            case TranslucentMotionLogLayout.COMPARE_LESS -> fragDepth > targetDepth;
            case TranslucentMotionLogLayout.COMPARE_EQUAL -> fragDepth == targetDepth;
            case TranslucentMotionLogLayout.COMPARE_LESS_EQUAL -> fragDepth >= targetDepth;
            case TranslucentMotionLogLayout.COMPARE_GREATER -> fragDepth < targetDepth;
            case TranslucentMotionLogLayout.COMPARE_NOT_EQUAL -> fragDepth != targetDepth;
            case TranslucentMotionLogLayout.COMPARE_GREATER_EQUAL -> fragDepth <= targetDepth;
            default -> false;
        };
    }

    private static boolean keyLess(FragmentNode a, FragmentNode b) {
        int c = Integer.compareUnsigned(a.drawIndex(), b.drawIndex());
        if (c != 0) return c < 0;
        c = Integer.compareUnsigned(a.instanceId(), b.instanceId());
        if (c != 0) return c < 0;
        return Integer.compareUnsigned(a.primitiveId(), b.primitiveId()) < 0;
    }

    /**
     * Simulates the full tiled overflow-proof replay loop (allocation cap, overflow detection, recursive
     * tile split, and 1x1 step-replay fallback) against an arbitrary arrival-ordered fragment stream.
     */
    public static TiledSimulationResult simulateTiledReplay(
            int width, int height, PixelState[][] initialPixels,
            List<ScreenFragment> unsortedFragments, int nodeCapacity, int maxTilePixels,
            boolean dynamicReadback, long maxPrimitives) {
        PixelState[][] out = new PixelState[height][width];
        for (int y = 0; y < height; y++) {
            System.arraycopy(initialPixels[y], 0, out[y], 0, width);
        }
        int tilesResolved = 0;
        int overflowSplits = 0;
        int singlePixelFallbacks = 0;
        int dropped = 0;

        TileRect region = new TileRect(0, 0, width, height);
        if (!dynamicReadback && maxPrimitives > nodeCapacity) {
            // Static Vulkan path routes straight to exact per-primitive step replay.
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    var pxFrags = collectSortedForPixel(unsortedFragments, x, y);
                    PixelState st = out[y][x];
                    for (var n : pxFrags) {
                        st = TranslucentMotionStepReplay.applyPrimitiveStep(st, n.fragDepth(), n.compare(),
                                n.writesColorDepth(), n.velocityEligible(), n.currentClipDepth(),
                                n.prevNdcX(), n.prevNdcY(), n.prevNdcZ(), n.prevNdcW());
                    }
                    out[y][x] = st;
                    singlePixelFallbacks++;
                }
            }
            return new TiledSimulationResult(out, 0, 0, singlePixelFallbacks, 0);
        }

        List<TileRect> initTiles = planInitialTiles(region, maxPrimitives, maxTilePixels, nodeCapacity, dynamicReadback);
        var queue = new ArrayDeque<TileRect>(initTiles.size());
        for (int i = initTiles.size() - 1; i >= 0; i--) queue.push(initTiles.get(i));

        while (!queue.isEmpty()) {
            TileRect tile = queue.pop();
            var captured = new ArrayList<ScreenFragment>();
            int counter = 0;
            for (var sf : unsortedFragments) {
                if (!tile.contains(sf.x(), sf.y())) continue;
                int slot = counter++;
                if (slot < nodeCapacity) {
                    captured.add(sf);
                }
            }
            if (counter == 0) continue;
            if (counter > nodeCapacity) {
                // Overflow detected -> NEVER resolve partial log!
                if (tile.width() > 1 || tile.height() > 1) {
                    TileRect[] halves = tile.split();
                    queue.push(halves[1]);
                    queue.push(halves[0]);
                    overflowSplits++;
                    continue;
                }
                // 1x1 pixel still overflowed -> exact O(1) per-primitive step fallback!
                var pxFrags = collectSortedForPixel(unsortedFragments, tile.x(), tile.y());
                PixelState st = out[tile.y()][tile.x()];
                for (var n : pxFrags) {
                    st = TranslucentMotionStepReplay.applyPrimitiveStep(st, n.fragDepth(), n.compare(),
                            n.writesColorDepth(), n.velocityEligible(), n.currentClipDepth(),
                            n.prevNdcX(), n.prevNdcY(), n.prevNdcZ(), n.prevNdcW());
                }
                out[tile.y()][tile.x()] = st;
                singlePixelFallbacks++;
                continue;
            }
            if (captured.size() != counter) dropped += (counter - captured.size());
            for (int py = tile.y(); py < tile.y() + tile.height(); py++) {
                for (int px = tile.x(); px < tile.x() + tile.width(); px++) {
                    var pxList = new ArrayList<FragmentNode>();
                    for (var sf : captured) {
                        if (sf.x() == px && sf.y() == py) pxList.add(sf.node());
                    }
                    if (!pxList.isEmpty()) {
                        out[py][px] = resolvePixel(out[py][px], pxList);
                    }
                }
            }
            tilesResolved++;
        }
        return new TiledSimulationResult(out, tilesResolved, overflowSplits, singlePixelFallbacks, dropped);
    }

    private static List<FragmentNode> collectSortedForPixel(List<ScreenFragment> frags, int x, int y) {
        var list = new ArrayList<FragmentNode>();
        for (var sf : frags) {
            if (sf.x() == x && sf.y() == y) list.add(sf.node());
        }
        list.sort((a, b) -> keyLess(a, b) ? -1 : (keyLess(b, a) ? 1 : 0));
        return list;
    }

    /**
     * Exact Java reference of {@code translucent_motion_log_resolve.fsh} (including both the {@code <= 32}
     * insertion-sort path and the {@code > 32} streaming minimum-extraction path), accepting fragments in
     * arbitrary GPU arrival order.
     */
    public static PixelState resolvePixel(PixelState initial, List<FragmentNode> unsortedFragments) {
        int count = unsortedFragments.size();
        if (count == 0) return initial;

        float[] state = new float[] {
                initial.colorDepth(),
                initial.motionDepth(),
                0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, -1.0f
        };
        boolean[] flags = new boolean[] { false, false }; // [0] = hasNewVelocity, [1] = invalidatedVelocity

        if (count <= LOCAL_SORT_CAP) {
            FragmentNode[] local = unsortedFragments.toArray(FragmentNode[]::new);
            for (int i = 1; i < count; i++) {
                FragmentNode current = local[i];
                int j = i;
                while (j > 0 && keyLess(current, local[j - 1])) {
                    local[j] = local[j - 1];
                    j--;
                }
                local[j] = current;
            }
            for (int i = 0; i < count; i++) {
                applyNode(local[i], state, flags);
            }
        } else {
            FragmentNode last = null;
            for (int step = 0; step < count; step++) {
                FragmentNode best = null;
                for (int scan = 0; scan < count; scan++) {
                    FragmentNode candidate = unsortedFragments.get(scan);
                    if ((last == null || keyLess(last, candidate))
                            && (best == null || keyLess(candidate, best))) {
                        best = candidate;
                    }
                }
                if (best == null) break;
                applyNode(best, state, flags);
                last = best;
            }
        }

        if (flags[0]) {
            return new PixelState(state[0], state[1], state[2], state[3], state[4], state[5], state[6], state[7]);
        }
        if (flags[1]) {
            return new PixelState(state[0], state[1], 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f);
        }
        return new PixelState(state[0], initial.motionDepth(),
                initial.prevNdcX(), initial.prevNdcY(), initial.prevNdcZ(), initial.prevNdcW(),
                initial.covClipDepth(), initial.covFragDepth());
    }

    private static void applyNode(FragmentNode node, float[] state, boolean[] flags) {
        float z = node.fragDepth();
        int compareCode = node.compareCode();
        if (!depthTest(compareCode, z, state[0])) {
            return;
        }
        float oldC = state[0];
        if (node.writesColorDepth()) {
            state[0] = z;
        }
        if (node.velocityEligible()) {
            if (depthTest(compareCode, z, state[1])) {
                state[1] = z;
                flags[0] = true;
                flags[1] = false;
                state[2] = node.prevNdcX();
                state[3] = node.prevNdcY();
                state[4] = node.prevNdcZ();
                state[5] = node.prevNdcW();
                state[6] = node.currentClipDepth();
                state[7] = z;
            }
        } else if (node.writesColorDepth()) {
            if (z > oldC) {
                if (z >= state[1] && z != state[1]) {
                    state[1] = z;
                    flags[0] = false;
                    flags[1] = true;
                }
            } else if (z < oldC) {
                if (state[1] <= oldC && z != state[1]) {
                    state[1] = z;
                    flags[0] = false;
                    flags[1] = true;
                }
            }
        }
    }
}
