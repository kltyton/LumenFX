package com.kltyton.lumenfx.client.render.material.vertex;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceException;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

/** Combines generated material functions with the renderer's actual vertex program. */
public final class MaterialVertexShaders implements ShaderSource {
    private record Key(Identifier renderer, Identifier material) { }
    private static final Pattern VERSION = Pattern.compile("(?m)^\\h*#\\h*version\\h+(\\d+)[^\\r\\n]*(?:\\r?\\n)?");
    private final ShaderSource host;
    private final Map<Key, Identifier> variants = new HashMap<>();
    private final Map<Identifier, String> sources = new HashMap<>();

    public MaterialVertexShaders(ShaderSource host) { this.host = Objects.requireNonNull(host, "host"); }

    public Identifier create(Identifier renderer, Identifier material) {
        return variants.computeIfAbsent(new Key(renderer, material), key -> {
            String base = require(key.renderer()), module = require(key.material());
            GlslSourceScanner.header(module);
            var version = VERSION.matcher(module);
            if (!version.find()) throw new GlslSourceException("Vertex material has no version: " + material);
            String body = module.substring(0, version.start()) + module.substring(version.end());
            if (VERSION.matcher(body).find()) throw new GlslSourceException("Vertex material has multiple versions: " + material);
            var baseVersion = VERSION.matcher(base);
            if (!baseVersion.find()) throw new GlslSourceException("Renderer vertex shader has no version: " + renderer);
            if (Integer.parseInt(version.group(1)) > Integer.parseInt(baseVersion.group(1)))
                base = base.substring(0, baseVersion.start(1)) + version.group(1) + base.substring(baseVersion.end(1));
            GlslSourceScanner.header(base);
            var main = GlslSourceScanner.functions(base, "main");
            if (main.size() != 1) throw new GlslSourceException("Expected one renderer vertex entry point: " + renderer);
            int insertion = main.getFirst().start();
            String source = base.substring(0, insertion) + "\n" + body + "\n" + base.substring(insertion);
            Identifier id = LumenFx.id("vertex_material/" + digest(source));
            sources.put(id, source);
            return id;
        });
    }

    @Override public String get(Identifier id, ShaderType type) {
        String source = type == ShaderType.VERTEX ? sources.get(id) : null;
        return source == null ? host.get(id, type) : source;
    }

    public void clear() { variants.clear(); sources.clear(); }

    private String require(Identifier id) {
        String source = host.get(id, ShaderType.VERTEX);
        if (source == null) throw new GlslSourceException("Vertex shader source is missing: " + id);
        return source;
    }

    private static String digest(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}
