package com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.UserParameterNames;
import com.kltyton.lumenfx.effect.niagara.texture.SparseVolumeInterfaceDefinition;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Keeps the requested frame separate from the last frame published to simulation. */
public final class NiagaraSparseVolumeTexture {
    public record Snapshot(@Nullable SparseVolumeTextureAsset asset, SparseVolumeTextureAsset.@Nullable Frame frame,
                           int frameCount) { }
    private final SparseVolumeInterfaceDefinition definition;
    private float frameRate = 30, requested;
    private int frameCount;
    private Snapshot snapshot;

    public NiagaraSparseVolumeTexture(SparseVolumeInterfaceDefinition definition) {
        this.definition = definition;
        snapshot = new Snapshot(null, null, 0);
    }
    public SparseVolumeInterfaceDefinition definition() { return definition; }
    public Snapshot snapshot() { return snapshot; }

    public void capture(Map<String, SparseVolumeTextureAsset> overrides, Map<Identifier, SparseVolumeTextureAsset> assets,
                        @Nullable NiagaraParameterStore parameters) {
        String parameter = definition.userParameter();
        String alias = SourceNames.canonical(UserParameterNames.display(parameter));
        var selected = overrides.get(alias);
        if (selected == null && parameters != null && !alias.isEmpty() && !alias.equals("none")) {
            var value = parameters.objectBinding(parameter).value();
            if (value instanceof SparseVolumeTextureAsset texture) selected = texture;
        }
        if (selected == null && definition.asset() != null)
            selected = Objects.requireNonNull(assets.get(definition.asset()), "Missing prepared Niagara sparse volume " + definition.asset());
        if (selected == null) return;
        frameCount = selected.frames().size();
        int index = selected.directFrame() ? 0 : (int)requested;
        if (index >= 0 && index < frameCount) {
            var frame = selected.frames().get(index);
            if (snapshot.asset() != selected || snapshot.frame() != frame)
                snapshot = new Snapshot(selected, frame, frameCount);
        }
    }

    public void request(int rate, int frame) {
        frameRate = Float.intBitsToFloat(rate);
        requested = Float.intBitsToFloat(frame);
    }

    public int frames() { return frameCount; }
    public int mips() { return snapshot.frame() == null ? 0 : snapshot.frame().mipCount(); }
    public int dimension(int axis, int mip) {
        var source = snapshot.asset();
        if (source == null) return 1;
        var extent = source.resolution().mip(Math.clamp(mip, 0, 31));
        return switch (axis) {
            case 0 -> extent.x(); case 1 -> extent.y(); case 2 -> extent.z();
            default -> throw new IllegalArgumentException("Invalid sparse volume dimension axis");
        };
    }
}
