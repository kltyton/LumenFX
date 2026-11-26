package com.kltyton.lumenfx.internal.photon.core.editor.resource;

import com.kltyton.lumenfx.platform.LumenPlatform;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

public final class FilePath implements IResourcePath {
    public final String path;
    public final @Nullable File file;
    public final @Nullable Identifier location;

    public static String normalizePath(String raw) {
        if (raw == null) return "";
        return raw.replace('\\', '/');
    }

    public static String toGameRelative(String raw) {
        String normalized = normalizePath(raw);
        Path game = LumenPlatform.gameDir().toAbsolutePath().normalize();
        try {
            Path p = Path.of(normalized).toAbsolutePath().normalize();
            if (p.startsWith(game)) {
                return normalizePath(game.relativize(p).toString());
            }
        } catch (Exception ignored) {
        }
        return normalized;
    }

    public static File resolveFile(String raw) {
        File direct = new File(raw);
        if (direct.isAbsolute()) return direct;
        return LumenPlatform.gameDir().resolve(raw).toFile();
    }

    public FilePath(String path) {
        this.path = normalizePath(path);
        this.file = resolveFile(this.path);
        this.location = null;
    }

    public FilePath(File file) {
        this.path = toGameRelative(file.getPath());
        this.file = file;
        this.location = null;
    }

    public FilePath(Identifier location) {
        this.location = location;
        this.path = location.toString();
        this.file = null;
    }

    @Override
    public ResourceProviderType getType() {
        return FILE_TYPE;
    }

    @Override
    public String getPath() {
        return path;
    }

    public @Nullable File getFile() {
        return file;
    }

    public @Nullable Identifier getLocation() {
        return location;
    }

    @Override
    public String getResourceName() {
        if (location != null) {
            String p = location.getPath();
            int slash = p.lastIndexOf('/');
            return slash >= 0 ? p.substring(slash + 1) : p;
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FilePath that)) return false;
        return Objects.equals(path, that.path) && Objects.equals(location, that.location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, location);
    }
}
