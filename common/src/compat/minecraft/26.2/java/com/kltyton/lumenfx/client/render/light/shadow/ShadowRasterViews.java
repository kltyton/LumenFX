package com.kltyton.lumenfx.client.render.light.shadow;

import com.mojang.blaze3d.ProjectionType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

/** Matrices used by source-space terrain and host-space feature submissions to the same depth views. */
public interface ShadowRasterViews {
    Matrix4fc raster(int index);
    Matrix4fc hostView(int index);
    Matrix4fc hostProjection(int index);
    CameraRenderState camera(int index, Vec3 origin);
    ProjectionType projectionType();
}
