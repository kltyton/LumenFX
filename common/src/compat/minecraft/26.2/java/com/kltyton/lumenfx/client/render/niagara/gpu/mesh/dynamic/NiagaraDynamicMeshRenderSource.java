package com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public record NiagaraDynamicMeshRenderSource(NiagaraDynamicDrawGeometry geometry, List<String> materialSources, List<String> materialParameters) {
    @FunctionalInterface
    public interface Resolver {
        @Nullable NiagaraDynamicMeshRenderSource resolve(CommandEncoder encoder, NiagaraGpuPrograms programs, String parameter);
    }

    public NiagaraDynamicMeshRenderSource {
        materialSources = List.copyOf(materialSources);
        materialParameters = List.copyOf(materialParameters);
    }

    public StaticMeshAsset.Bounds bounds() {
        var box = geometry.frame().bounds();
        var origin = new Vec3((box.minX() + box.maxX()) * .5, (box.minY() + box.maxY()) * .5, (box.minZ() + box.maxZ()) * .5);
        var extent = new Vec3((box.maxX() - box.minX()) * .5, (box.maxY() - box.minY()) * .5, (box.maxZ() - box.minZ()) * .5);
        return new StaticMeshAsset.Bounds(origin, extent, (float)extent.length());
    }
}
