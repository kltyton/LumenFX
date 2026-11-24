package com.kltyton.lumenfx.client.resource.texture;

import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureArray;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureVolume;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureCube;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureUpload;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * prepare(): worker-thread resource decoding of the encoded mip chain.
 * upload(): render-thread upload of native blocks and source sampler creation.
 */
public final class EffectTextureLoader {
    public record PreparedArrayTarget(Identifier id, RenderTargetTextureDefinition surface, int layers, boolean clearAll,
                                      float targetGamma, boolean forceLinearGamma) {
        public PreparedArrayTarget {
            if (layers < 1 || surface.autoMips() || !Float.isFinite(targetGamma))
                throw new IllegalArgumentException("Invalid single-mip texture render target array");
        }
    }
    public record PreparedVolumeTarget(Identifier id, RenderTargetTextureDefinition surface, int depth,
                                       float targetGamma, boolean forceLinearGamma) {
        public PreparedVolumeTarget {
            if (depth < 1 || surface.autoMips() || !Float.isFinite(targetGamma))
                throw new IllegalArgumentException("Invalid single-mip volume render target");
        }
    }
    public record PreparedCubeTarget(Identifier id, RenderTargetTextureDefinition surface,
                                     float targetGamma, boolean forceLinearGamma) {
        public PreparedCubeTarget {
            if (surface.width() != surface.height() || !Float.isFinite(targetGamma))
                throw new IllegalArgumentException("Invalid cube render target");
        }
    }

    private EffectTextureLoader() { }

    public static PreparedArrayTarget prepareArrayTarget(Identifier id, ResourceManager resources) {
        try (var reader = resources.getResourceOrThrow(id).openAsReader()) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            var layers = data.getAsJsonPrimitive("array_layers");
            if (layers == null || !layers.isNumber()) throw new IllegalArgumentException("Render target array has no layer count");
            return new PreparedArrayTarget(id, RenderTargetTextureDefinition.read(data), layers.getAsBigDecimal().intValueExact(),
                    data.get("clear_all_slices").getAsBoolean(), data.get("target_gamma").getAsFloat(),
                    data.get("force_linear_gamma").getAsBoolean());
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot prepare render target texture array " + id, error);
        }
    }

    public static EffectTexture uploadArrayTarget(PreparedArrayTarget prepared) {
        RenderSystem.assertOnRenderThread();
        try (var runtime = ComputeRuntime.create(RenderSystem.getDevice())) {
            return new EffectTexture(prepared.id(), new RenderTargetTextureArray(prepared.id(), runtime,
                    prepared.surface(), prepared.layers(), prepared.clearAll(), prepared.targetGamma(), prepared.forceLinearGamma()));
        }
    }

    public static PreparedVolumeTarget prepareVolumeTarget(Identifier id, ResourceManager resources) {
        try (var reader = resources.getResourceOrThrow(id).openAsReader()) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            var depth = data.getAsJsonPrimitive("depth");
            if (depth == null || !depth.isNumber()) throw new IllegalArgumentException("Volume render target has no depth");
            return new PreparedVolumeTarget(id, RenderTargetTextureDefinition.read(data), depth.getAsBigDecimal().intValueExact(),
                    data.get("target_gamma").getAsFloat(), data.get("force_linear_gamma").getAsBoolean());
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot prepare volume render target " + id, error);
        }
    }

    public static EffectTexture uploadVolumeTarget(PreparedVolumeTarget prepared) {
        RenderSystem.assertOnRenderThread();
        return new EffectTexture(prepared.id(), new RenderTargetTextureVolume(prepared.id(), prepared.surface(),
                prepared.depth(), prepared.targetGamma(), prepared.forceLinearGamma()));
    }

    public static PreparedCubeTarget prepareCubeTarget(Identifier id, ResourceManager resources) {
        try (var reader = resources.getResourceOrThrow(id).openAsReader()) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            return new PreparedCubeTarget(id, RenderTargetTextureDefinition.read(data),
                    data.get("target_gamma").getAsFloat(), data.get("force_linear_gamma").getAsBoolean());
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot prepare cube render target " + id, error);
        }
    }

    public static EffectTexture uploadCubeTarget(PreparedCubeTarget prepared) {
        RenderSystem.assertOnRenderThread();
        return new EffectTexture(prepared.id(), new RenderTargetTextureCube(prepared.id(), prepared.surface(),
                prepared.targetGamma(), prepared.forceLinearGamma()));
    }

    public static PreparedTexture prepare(Identifier png, ResourceManager resources) {
        Identifier metadataId = metadataId(png);
        Optional<Resource> metadataResource = resources.getResource(metadataId);
        if (metadataResource.isEmpty()) return null;

        byte[] metadataBytes;
        try (InputStream input = metadataResource.get().open()) {
            metadataBytes = input.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read texture metadata " + metadataId, error);
        }
        byte[] metadataDigest = sha256Bytes(metadataBytes);
        var document = JsonParser.parseString(new String(metadataBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        TextureMetadata metadata = TextureMetadata.parse(document);
        String sourceType = document.has("source") ? document.get("source").getAsString() : "";
        boolean array = sourceType.equals("unreal-texture2d-array-source");
        boolean cube = sourceType.equals("unreal-texture-cube-source");
        boolean cubeArray = sourceType.equals("unreal-texture-cube-array-source");
        boolean volume = sourceType.equals("unreal-texture-volume-source");
        int arrayLayers = document.has("array_layers") ? document.get("array_layers").getAsInt() : 0;
        if (array ? arrayLayers < 1 : document.has("array_layers"))
            throw new IllegalArgumentException("Invalid source texture array layer count: " + metadataId);
        int cubes = document.has("cube_array_layers") ? document.get("cube_array_layers").getAsBigDecimal().intValueExact() : 0;
        if (cubeArray ? cubes < 1 || cubes > Integer.MAX_VALUE / 6 : document.has("cube_array_layers"))
            throw new IllegalArgumentException("Invalid source cubemap array layer count: " + metadataId);
        var source = metadata.sourceInput();
        Identifier sourceId = source == null ? png : Identifier.parse(source.resource());
        Resource sourceResource = resources.getResource(sourceId).orElseThrow(
                () -> new IllegalArgumentException("Missing effect texture source " + sourceId));
        byte[] bytes;
        try (InputStream input = sourceResource.open()) {
            bytes = input.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read effect texture source " + sourceId, error);
        }
        String hash = sha256(bytes);
        String expected = source == null ? metadata.pngSha256() : source.sha256();
        if (!expected.equalsIgnoreCase(hash))
            throw new IllegalStateException("Texture metadata hash mismatch for " + png);

        if ("unity-texture2d-png".equals(sourceType)) {
            return prepareRawPng(png, metadata, bytes);
        }

        Identifier mipData = Identifier.parse(metadata.mipData());
        try (InputStream input = resources.getResourceOrThrow(mipData).open()) {
            EncodedTextureData data = EncodedTextureData.read(ByteBuffer.wrap(input.readAllBytes()), metadata, metadataDigest,
                    cubeArray ? cubes * 6 : cube ? 6 : Math.max(1, arrayLayers), volume);
            var addressZ = volume ? TextureMetadata.Address.valueOf(document.get("address_z").getAsString().toUpperCase(Locale.ROOT)) : null;
            return new PreparedTexture(png, data.metadata(), data.mips(), arrayLayers, cube, data.volumeDepth(), addressZ, cubes);
        } catch (IOException | BufferUnderflowException | IllegalArgumentException error) {
            throw new IllegalStateException("Cannot read encoded effect texture " + mipData, error);
        }
    }

    public static EffectTexture upload(PreparedTexture prepared) {
        RenderSystem.assertOnRenderThread();
        TextureMetadata metadata = prepared.metadata();
        int mipLevels = prepared.mips().size();
        if (prepared.volume()) {
            var storage = new NativeTextureStorage(TextureStorageFormat.from(metadata.cookedFormat()), metadata.srgb());
            var sampler = new EffectSamplerFactory.Settings(metadata.addressX(), metadata.addressY(),
                    metadata.minMag(), metadata.mip(), metadata.maxAnisotropy());
            var volume = ComputeVolume.createSampled(prepared.id().toString(), metadata.width(), metadata.height(),
                    prepared.volumeDepth(), prepared.mips(), storage,
                    new ComputeVolume.Sampling(sampler, Objects.requireNonNull(prepared.volumeAddressZ())));
            return new EffectTexture(prepared.id(), volume, metadata);
        }
        if (prepared.array()) {
            var array = NativeTextureUpload.uploadArray(RenderSystem.getDevice(), prepared);
            try { return new EffectTexture(prepared.id(), array, EffectSamplerFactory.create(metadata, mipLevels), metadata); }
            catch (RuntimeException | Error failure) { array.close(); throw failure; }
        }
        GpuTexture texture = NativeTextureUpload.upload(RenderSystem.getDevice(), prepared);
        GpuTextureView view = null;
        GpuSampler sampler = null;
        try {
            view = NativeTextureUpload.createView(RenderSystem.getDevice(), texture);
            sampler = prepared.cubeArray() ? EffectSamplerFactory.create(new EffectSamplerFactory.Settings(metadata.addressX(),
                    metadata.addressY(), metadata.minMag(), metadata.mip(), metadata.maxAnisotropy()), TextureMetadata.Address.CLAMP,
                    mipLevels, 0f) : EffectSamplerFactory.create(metadata, mipLevels);
            return new EffectTexture(prepared.id(), texture, view, sampler, metadata);
        } catch (RuntimeException error) {
            if (sampler != null) sampler.close();
            if (view != null) view.close();
            texture.close();
            throw error;
        }
    }

    private static PreparedTexture prepareRawPng(Identifier png, TextureMetadata metadata, byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw new IllegalArgumentException("Unreadable PNG image: " + png);
            int width = image.getWidth();
            int height = image.getHeight();
            if (width != metadata.width() || height != metadata.height())
                throw new IllegalArgumentException("PNG dimensions do not match texture metadata for " + png);
            ByteBuffer level0 = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 4));
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    level0.put((byte) ((argb >> 16) & 0xff));
                    level0.put((byte) ((argb >> 8) & 0xff));
                    level0.put((byte) (argb & 0xff));
                    level0.put((byte) ((argb >>> 24) & 0xff));
                }
            }
            level0.flip();
            List<ByteBuffer> mips = new ArrayList<>();
            mips.add(level0);
            if (metadata.mipMode() != TextureMetadata.MipMode.NO_MIPMAPS) {
                ByteBuffer prev = level0;
                int pw = width, ph = height;
                while (pw > 1 || ph > 1) {
                    int nw = Math.max(1, pw >> 1), nh = Math.max(1, ph >> 1);
                    ByteBuffer next = downsampleRgba8(prev, pw, ph, nw, nh, metadata.srgb());
                    mips.add(next);
                    prev = next;
                    pw = nw;
                    ph = nh;
                }
            }
            TextureMetadata resolved = metadata.withStorage(width, height, metadata.srgb(), "RGBA8",
                    metadata.minMag(), metadata.mip(), metadata.maxAnisotropy());
            return new PreparedTexture(png, resolved, mips, 0, false);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot decode PNG effect texture " + png, error);
        }
    }

    private static ByteBuffer downsampleRgba8(ByteBuffer src, int sw, int sh, int dw, int dh, boolean srgb) {
        ByteBuffer dst = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(dw, dh), 4));
        for (int dy = 0; dy < dh; dy++) {
            int sy0 = Math.min(sh - 1, dy * 2), sy1 = Math.min(sh - 1, sy0 + 1);
            for (int dx = 0; dx < dw; dx++) {
                int sx0 = Math.min(sw - 1, dx * 2), sx1 = Math.min(sw - 1, sx0 + 1);
                double r = 0, g = 0, b = 0, a = 0;
                int[] coords = {sx0, sy0, sx1, sy0, sx0, sy1, sx1, sy1};
                for (int k = 0; k < 4; k++) {
                    int off = (coords[k * 2 + 1] * sw + coords[k * 2]) * 4;
                    double cr = (src.get(off) & 0xff) / 255.0;
                    double cg = (src.get(off + 1) & 0xff) / 255.0;
                    double cb = (src.get(off + 2) & 0xff) / 255.0;
                    double ca = (src.get(off + 3) & 0xff) / 255.0;
                    r += srgb ? srgbToLinear(cr) : cr;
                    g += srgb ? srgbToLinear(cg) : cg;
                    b += srgb ? srgbToLinear(cb) : cb;
                    a += ca;
                }
                r *= 0.25; g *= 0.25; b *= 0.25; a *= 0.25;
                dst.put((byte) Math.clamp(Math.round((srgb ? linearToSrgb(r) : r) * 255.0), 0, 255));
                dst.put((byte) Math.clamp(Math.round((srgb ? linearToSrgb(g) : g) * 255.0), 0, 255));
                dst.put((byte) Math.clamp(Math.round((srgb ? linearToSrgb(b) : b) * 255.0), 0, 255));
                dst.put((byte) Math.clamp(Math.round(a * 255.0), 0, 255));
            }
        }
        dst.flip();
        return dst;
    }

    private static double srgbToLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double linearToSrgb(double c) {
        return c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(Math.max(0.0, c), 1.0 / 2.4) - 0.055;
    }

    private static Identifier metadataId(Identifier png) {
        String path = png.getPath();
        if (path.endsWith(".lftex.json")) return png;
        if (!path.endsWith(".png")) throw new IllegalArgumentException("Effect texture is not a PNG: " + png);
        return Identifier.fromNamespaceAndPath(png.getNamespace(), path.substring(0, path.length() - 4) + ".lftex.json");
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Bytes(bytes));
    }

    private static byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
