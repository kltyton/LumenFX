package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.ProducerLayout;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Layer;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTexturePages.Page;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTextureTileSource.Request;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/** Routes the immutable coarse levels of a placed RVT to its cooked uploading producer. */
final class RuntimeVirtualTextureLowMips {
    static final class IncompatibleLayout extends IllegalArgumentException {
        IncompatibleLayout(String message) { super(message); }
    }

    private final VirtualTextureTileSource source;
    private final int firstMip;
    private final int transition;
    private final ProducerLayout layout;
    private final Semaphore budget;

    RuntimeVirtualTextureLowMips(VirtualTextureTileSource source, RuntimeVirtualTextureDefinition definition,
                                 ProducerLayout scene, List<Layer> formats, boolean only, Semaphore budget) {
        this.source = source;
        this.budget = budget;
        var directory = source.directory();
        var cooked = directory.layout();
        if (cooked.tileSize() != scene.tileSize() || cooked.border() != scene.border()
                || cooked.blocksX() != 1 || cooked.blocksY() != 1 || directory.layers().size() != formats.size()
                || Integer.bitCount(cooked.pagesX()) != 1 || Integer.bitCount(cooked.pagesY()) != 1)
            throw new IncompatibleLayout("RVT streaming tile layout differs from the placed producer: " + source.id());
        for (int layer = 0; layer < formats.size(); layer++) {
            var storage = formats.get(layer);
            var baked = directory.layers().get(layer);
            if (baked.format() != RuntimeVirtualTextureStorage.storage(storage) || baked.srgb() != storage.srgb())
                throw new IncompatibleLayout("RVT streaming layer format differs from the placed producer: " + source.id() + " layer " + layer);
        }
        int ownerLevels = levels(Math.max(scene.pagesX(), scene.pagesY()));
        int sourceLevels = levels(Math.max(cooked.pagesX(), cooked.pagesY()));
        firstMip = Math.max(0, sourceLevels - ownerLevels);
        if (firstMip >= directory.mips()) throw new IncompatibleLayout("No cooked RVT mip matches the producer size: " + source.id());
        transition = only ? 0 : ownerLevels - (sourceLevels - firstMip);
        layout = only ? definition.streamedLayout(scene, Math.max(1, cooked.pagesX() >> firstMip),
                Math.max(1, cooked.pagesY() >> firstMip), sourceLevels - firstMip) : scene;
    }

    ProducerLayout layout() { return layout; }
    boolean hasLivePages() { return transition != 0; }
    int fallbackColor(int layer) { return source.directory().layers().get(layer).fallbackColor(); }
    boolean owns(Page page) { return page.mip() >= transition; }
    boolean available(Page page) { return source.directory().range(sourcePage(page), 0) != null; }

    @Nullable CompletableFuture<RuntimeVirtualTexturePageRenderer.Page> submit(Page page, BooleanSupplier cancelled) {
        if (!budget.tryAcquire()) return null;
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return read(sourcePage(page), cancelled); }
                finally { budget.release(); }
            }, Util.backgroundExecutor().forName("LumenFX RVT streaming tile I/O"));
        } catch (RuntimeException | Error failure) {
            budget.release();
            throw failure;
        }
    }

    private RuntimeVirtualTexturePageRenderer.@Nullable Page read(Page page, BooleanSupplier cancelled) {
        var directory = source.directory();
        List<Request> requests = new ArrayList<>(directory.layers().size());
        for (int layer = 0; layer < directory.layers().size(); layer++)
            requests.add(new Request(page, layer, Objects.requireNonNull(directory.range(page, layer))));
        try {
            var tiles = source.readTiles(requests, cancelled);
            if (cancelled.getAsBoolean()) return null;
            byte[][] layers = new byte[requests.size()][];
            for (var tile : tiles) layers[tile.layer()] = tile.data();
            return new RuntimeVirtualTexturePageRenderer.Page(Arrays.asList(layers));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not stream RVT mip " + page + " of " + source.id(), failure);
        }
    }

    private Page sourcePage(Page page) { return new Page(page.mip() - transition + firstMip, page.x(), page.y()); }
    private static int levels(int side) { return Integer.SIZE - Integer.numberOfLeadingZeros(side - 1); }
}
