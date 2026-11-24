package com.kltyton.lumenfx.client.simulation.niagara.bounds;

import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Static source mesh extents and pivot contributions used by Niagara's dynamic bounds. */
public record NiagaraMeshBounds(Vec3 extent, Vec3 localOffset, Vec3 worldOffset) {
    public static List<NiagaraMeshBounds> collect(List<EmitterDefinition> renderers) {
        var result = new ArrayList<NiagaraMeshBounds>();
        for (var renderer : renderers) {
            if (renderer.renderer() != EmitterDefinition.Renderer.MESH) continue;
            NiagaraMeshBounds bounds = rendererBounds(renderer);
            if (bounds != null) result.add(bounds);
        }
        return List.copyOf(result);
    }

    private static @Nullable NiagaraMeshBounds rendererBounds(EmitterDefinition renderer) {
        Vec3 extent = Vec3.ZERO, local = Vec3.ZERO, world = Vec3.ZERO;
        boolean hadMesh = false;
        for (var entry : renderer.meshSettings().entries()) {
            if (entry.asset() == null) continue;
            hadMesh = true;
            var bounds = entry.asset().extendedBounds();
            Vec3 lower = bounds.origin().subtract(bounds.extent()).multiply(entry.scale());
            Vec3 upper = bounds.origin().add(bounds.extent()).multiply(entry.scale());
            Vec3 offset = entry.pivot();
            if (entry.pivotSpace() == MeshRendererSettings.PivotSpace.MESH) {
                lower = lower.add(offset);
                upper = upper.add(offset);
            } else if (entry.pivotSpace() == MeshRendererSettings.PivotSpace.LOCAL
                    || entry.pivotSpace() == MeshRendererSettings.PivotSpace.SIMULATION && renderer.localSpace())
                local = maximum(local, absolute(offset));
            else world = maximum(world, absolute(offset));
            extent = maximum(extent, maximum(absolute(lower), absolute(upper)));
        }
        if (!hadMesh) {
            if (renderer.meshSettings().meshesBinding() == null) return null;
            extent = new Vec3(1, 1, 1);
        }
        return new NiagaraMeshBounds(extent, local, world);
    }

    public Vec3 expansion(Vec3 particleScale, boolean localSpace, Matrix4dc offsetTransform) {
        double radius = Math.max(1.e-4, extent.multiply(particleScale).length());
        Vec3 source = localSpace ? worldOffset : localOffset;
        var offset = offsetTransform.transformDirection(new Vector3d(source.x, source.y, source.z));
        return maximum(localSpace ? localOffset : worldOffset, new Vec3(offset.x, offset.y, offset.z))
                .add(radius, radius, radius);
    }

    public static Vec3 maximum(Vec3 first, Vec3 second) {
        return new Vec3(Math.max(first.x, second.x), Math.max(first.y, second.y), Math.max(first.z, second.z));
    }
    private static Vec3 absolute(Vec3 value) { return new Vec3(Math.abs(value.x), Math.abs(value.y), Math.abs(value.z)); }
}
