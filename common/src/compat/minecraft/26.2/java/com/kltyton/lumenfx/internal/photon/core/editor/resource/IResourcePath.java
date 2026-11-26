package com.kltyton.lumenfx.internal.photon.core.editor.resource;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

public interface IResourcePath {
    Pattern PATH_WITH_TYPE_PATTERN = Pattern.compile("^([a-zA-Z0-9-_]+)\\((.+)\\)$");

    ResourceProviderType BUILTIN_TYPE = new ResourceProviderType() {
        @Override public String getTypeName() { return "builtin"; }
        @Override public IResourcePath createFullPath(String path) { return new BuiltinPath(path); }
    };

    ResourceProviderType FILE_TYPE = new ResourceProviderType() {
        @Override public String getTypeName() { return "file"; }
        @Override public IResourcePath createFullPath(String path) { return new FilePath(path); }
    };

    Codec<IResourcePath> V0 = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("built-in").forGetter(path -> path.getType() == BUILTIN_TYPE),
            Codec.STRING.fieldOf("path").forGetter(IResourcePath::getPath)
    ).apply(instance, (builtin, path) -> builtin ? new BuiltinPath(path) : new FilePath(path)));

    Codec<IResourcePath> V1 = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("type").forGetter(path -> path.getType().getTypeName()),
            Codec.STRING.fieldOf("path").forGetter(IResourcePath::getPath)
    ).apply(instance, (type, path) -> "builtin".equals(type) ? new BuiltinPath(path)
            : "pack".equals(type) ? new FilePath(Identifier.parse(path)) : new FilePath(path)));

    Codec<IResourcePath> V2 = Codec.STRING.comapFlatMap(
            s -> {
                var parsed = parse(s);
                return parsed != null ? DataResult.success(parsed) : DataResult.error(() -> "Invalid resource path: " + s);
            },
            IResourcePath::getPathWithType
    );

    Codec<IResourcePath> CODEC = Codec.withAlternative(V2, Codec.withAlternative(V1, V0));

    ResourceProviderType getType();

    String getPath();

    String getResourceName();

    default String getPathWithType() {
        return "%s(%s)".formatted(getType().getTypeName(), getPath());
    }

    @Nullable
    static IResourcePath parse(@Nullable String pathWithType) {
        if (pathWithType == null) return null;
        var trimmed = pathWithType.trim();
        var matcher = PATH_WITH_TYPE_PATTERN.matcher(trimmed);
        if (!matcher.matches()) return null;
        var type = matcher.group(1);
        var resourcePath = matcher.group(2);
        return switch (type) {
            case "builtin" -> new BuiltinPath(resourcePath);
            case "file" -> new FilePath(resourcePath);
            case "pack" -> new FilePath(Identifier.parse(resourcePath));
            default -> null;
        };
    }
}
