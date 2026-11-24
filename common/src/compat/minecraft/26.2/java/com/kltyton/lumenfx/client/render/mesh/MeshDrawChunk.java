package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry.Section;
import com.mojang.blaze3d.buffers.GpuBuffer;

/** A material section with GPU geometry ready for the Niagara mesh vertex factory. */
public interface MeshDrawChunk {
    Section section();
    int indexCount();
    GpuBuffer vertices();
    GpuBuffer indices();
    GpuBuffer info();
}
