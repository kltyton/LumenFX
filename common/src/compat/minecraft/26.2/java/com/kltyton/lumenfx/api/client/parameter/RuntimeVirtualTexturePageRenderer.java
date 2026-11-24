package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.PageView;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.Volume;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.multiplayer.ClientLevel;

/** A scene's material page pass. A completed page owns every layer's packed bytes. */
public interface RuntimeVirtualTexturePageRenderer {
    enum Format { BGRA8, RGB565, G16, BC1, BC3, BC4, BC5 }
    record Layer(Format format, boolean srgb, boolean yCoCg) { }
    record Request(RuntimeVirtualTextureDefinition definition, Volume volume, PageView view, List<Layer> layers) {
        public Request { layers = List.copyOf(layers); }
    }
    record Page(List<byte[]> layers) {
        public Page { layers = layers.stream().map(byte[]::clone).toList(); }
        @Override public List<byte[]> layers() { return layers.stream().map(byte[]::clone).toList(); }
        public int layerCount() { return layers.size(); }
        public ByteBuffer layer(int index) { return ByteBuffer.wrap(layers.get(index)).asReadOnlyBuffer(); }
    }

    /** True during a scene phase in which the host can submit its material raster work. */
    boolean ready(ClientLevel level);

    /** Called on the render thread. Completion can follow asynchronous GPU work and readback. */
    CompletionStage<Page> render(ClientLevel level, Request request);
}
