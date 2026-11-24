package com.kltyton.lumenfx.client.render.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Feedback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Uint4;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

/** Collects actual material fragment page requests and returns them to the live scene producer. */
final class MaterialVirtualTextureFeedback implements AutoCloseable {
    private static final int STRIDE = 16;
    private static final int HEADER = STRIDE;
    private static final int TRANSFERS = 4;
    private static final int MAX_RECORDS = 1 << 18;
    private record Producer(Consumer<Feedback> request, int width, int height, double widthUV, double heightUV) { }
    private record Page(int producer, int x, int y, int mip) { }
    private static final class Transfer {
        @Nullable GpuBuffer staging;
        long ticket;
    }

    private final Transfer[] transfers = new Transfer[TRANSFERS];
    private final Map<Object, Integer> ids = new IdentityHashMap<>();
    private final List<Producer> producers = new ArrayList<>();
    private @Nullable GpuBuffer storage;
    private @Nullable GlBinding glBinding;
    private long nextTicket;
    private long generation;
    private int write;
    private int capacity;
    private boolean active;

    MaterialVirtualTextureFeedback() {
        for (int index = 0; index < transfers.length; index++) transfers[index] = new Transfer();
    }

    void begin(int width, int height) {
        // One jittered pixel per 16x16 tile, with room for overdraw on the same tile.
        long tileCount = ((long)width + 15) / 16 * (((long)height + 15) / 16);
        capacity = (int)Math.clamp(tileCount * 8, 64, MAX_RECORDS);
    }

    int register(Object identity, Consumer<Feedback> request, Uint4 pageTable, int adaptiveBias) {
        return ids.computeIfAbsent(identity, ignored -> {
            int width = (int)Float.intBitsToFloat(pageTable.z()) << adaptiveBias;
            int height = (int)Float.intBitsToFloat(pageTable.w()) << adaptiveBias;
            double scaleU = Float.intBitsToFloat(pageTable.x());
            double scaleV = Float.intBitsToFloat(pageTable.y());
            if (width <= 0 || height <= 0 || !(scaleU > 0) || !(scaleV > 0))
                throw new IllegalArgumentException("Virtual texture feedback needs a live page grid");
            producers.add(new Producer(request, width, height, width * scaleU, height * scaleV));
            return producers.size();
        });
    }

    void prepare(CommandEncoder encoder) {
        if (active) return;
        int bytes = byteSize();
        var device = RenderSystem.getDevice();
        if (storage == null || storage.size() < bytes) {
            if (device.backend instanceof VulkanDevice vulkan) {
                try (var stack = MemoryStack.stackPush()) {
                    var supported = VkPhysicalDeviceFeatures.calloc(stack);
                    VK12.vkGetPhysicalDeviceFeatures(vulkan.vkDevice().getPhysicalDevice(), supported);
                    if (!supported.fragmentStoresAndAtomics())
                        throw new IllegalStateException("Virtual texture feedback needs Vulkan fragment stores and atomics");
                }
            }
            var replacement = device.createBuffer(() -> "LumenFX material VT feedback",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), bytes);
            retire(storage);
            storage = replacement;
        }
        try (var stack = MemoryStack.stackPush()) {
            ByteBuffer zero = stack.calloc(HEADER);
            encoder.writeToBuffer(storage.slice(0, HEADER), zero);
        }
        active = true;
    }

    void bind(RenderPass pass) {
        var buffer = java.util.Objects.requireNonNull(storage, "Material feedback storage was not prepared");
        var range = buffer.slice(0, byteSize());
        pass.setUniform(VirtualTextureFeedbackLayout.NAME, range);
        if (buffer instanceof GlBuffer gl && glBinding == null) {
            glBinding = GlBinding.capture();
            GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, VirtualTextureFeedbackLayout.GL_SLOT, gl.handle(), 0, range.length());
        }
    }

    void finish() {
        try {
            if (active && !producers.isEmpty()) transfer();
        } finally {
            if (glBinding != null) { glBinding.restore(); glBinding = null; }
            active = false;
            producers.clear();
            ids.clear();
        }
    }

    private void transfer() {
        var device = RenderSystem.getDevice();
        int bytes = byteSize();
        var slot = transfers[write];
        write = (write + 1) % transfers.length;
        if (slot.staging == null || slot.staging.size() < bytes) {
            var replacement = device.createBuffer(() -> "LumenFX material VT feedback readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
            retire(slot.staging);
            slot.staging = replacement;
        }
        GpuBuffer staging = slot.staging;
        if (device.backend instanceof GlDevice)
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        // The Vulkan encoder's render-pass end supplies the graphics-to-transfer memory dependency.
        var encoder = device.createCommandEncoder();
        encoder.copyToBuffer(java.util.Objects.requireNonNull(storage).slice(0, bytes), staging.slice(0, bytes));
        long ticket = slot.ticket = ++nextTicket;
        long epoch = generation;
        var sources = List.copyOf(producers);
        RenderSystem.queueFencedTask(() -> {
            if (slot.ticket != ticket || generation != epoch) return;
            decode(staging, bytes, sources);
        });
    }

    private static void decode(GpuBuffer staging, int bytes, List<Producer> producers) {
        var unique = new HashSet<Page>();
        try (var mapped = staging.slice(0, bytes).map(true, false)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            int count = (int)Math.min(Integer.toUnsignedLong(data.getInt()), (bytes - HEADER) / STRIDE);
            data.position(HEADER);
            for (int index = 0; index < count; index++) {
                int producer = data.getInt() - 1;
                int x = data.getInt(), y = data.getInt(), mip = data.getInt();
                if (producer < 0 || producer >= producers.size() || mip < 0) continue;
                var source = producers.get(producer);
                if (x < 0 || y < 0 || x >= source.width() || y >= source.height()) continue;
                int shift = Math.min(mip, 30);
                if (unique.add(new Page(producer, x >> shift, y >> shift, mip))) {
                    double u = (x + .5) / source.widthUV();
                    double v = (y + .5) / source.heightUV();
                    source.request().accept(new Feedback(u, v, u, v, mip));
                }
            }
        }
    }

    private int byteSize() { return HEADER + capacity * STRIDE; }
    private static void retire(@Nullable GpuBuffer buffer) { if (buffer != null) RenderSystem.queueFencedTask(buffer::close); }

    void invalidateWorld() { generation++; }

    @Override public void close() {
        invalidateWorld();
        retire(storage);
        storage = null;
        for (var transfer : transfers) {
            retire(transfer.staging);
            transfer.staging = null;
        }
    }

    private record GlBinding(int generic, int index, long start, long size) {
        static GlBinding capture() {
            int slot = VirtualTextureFeedbackLayout.GL_SLOT;
            try (var stack = MemoryStack.stackPush()) {
                var value = stack.mallocLong(1);
                GL32C.glGetInteger64i_v(GL43C.GL_SHADER_STORAGE_BUFFER_START, slot, value);
                long start = value.get(0);
                GL32C.glGetInteger64i_v(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, slot, value);
                return new GlBinding(GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING),
                        GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, slot), start, value.get(0));
            }
        }
        void restore() {
            int slot = VirtualTextureFeedbackLayout.GL_SLOT;
            if (index == 0 || size == 0) GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, slot, index);
            else GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, slot, index, start, size);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, generic);
        }
    }
}
