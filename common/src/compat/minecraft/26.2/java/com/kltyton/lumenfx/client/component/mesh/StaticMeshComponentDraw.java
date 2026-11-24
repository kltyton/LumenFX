package com.kltyton.lumenfx.client.component.mesh;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.render.EffectMesh;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshLodSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshView;
import com.kltyton.lumenfx.client.render.mesh.MeshInstanceBuilder;
import com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveFrame;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.render.ParticleMotionSettings;
import com.kltyton.lumenfx.effect.render.depth.CustomDepthStencil;
import com.kltyton.lumenfx.effect.render.light.PrimitiveLighting;
import com.kltyton.lumenfx.effect.render.material.PrimitiveCustomData;
import com.kltyton.lumenfx.effect.render.mesh.MeshBatchGeometry;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Frozen component geometry reused by the display and particle shadow views. */
public record StaticMeshComponentDraw(EffectDefinition effect, String fallbackMaterial, MeshRendererSettings renderer,
        MeshRendererSettings.Entry entry, Pose pose, Pose previous, Vec3 actor, PrimitiveLighting lighting,
        CustomDepthStencil depthStencil, boolean receivesDecals, boolean visible, int forcedLod, int minimumLod) {
    public void append(MeshView view, Vec3 camera, MeshInstanceBuilder.Streams streams) {
        boolean shadow = view.shadowPass();
        if (shadow ? !lighting.castsDynamicShadow() : !visible) return;
        var asset = Objects.requireNonNull(entry.asset());
        var bounds = asset.extendedBounds();
        var centre = pose.transformPosition(new Vec3(bounds.origin().x, bounds.origin().z, bounds.origin().y).scale(.01));
        double depth = centre.distanceToSqr(camera);
        float radius = (float)(bounds.radius() * Math.max(Math.abs(pose.scaleX()),
                Math.max(Math.abs(pose.scaleY()), Math.abs(pose.scaleZ()))));
        if (!view.visible(centre.subtract(camera), radius * .01)) return;
        view = view.withLodPolicy(effect.materials().get(fallbackMaterial).renderSettings().meshLod());
        int lastLod = asset.lods().size() - 1;
        if (lastLod < 0) return;
        int floor = Math.clamp(Math.max(minimumLod, MeshLodSelection.minimum(asset, view)), 0, lastLod);
        int selected = forcedLod > 0 ? forcedLod - 1 : MeshLodSelection.component(asset, view, depth * 10000, radius, 1);
        var resident = asset.resident();
        var geometry = asset.residentGeometry(asset.firstResidentLod(Math.clamp(selected, floor, lastLod), resident), resident);
        if (geometry == null) return;
        var extent = bounds.extent();
        var origin = bounds.origin();
        var localBounds = new NiagaraBounds(origin.x - extent.x, origin.y - extent.y, origin.z - extent.z,
                origin.x + extent.x, origin.y + extent.y, origin.z + extent.z);
        var materialPrimitive = new MaterialPrimitiveFrame(pose, previous, lighting, depthStencil, false,
                () -> localBounds, actor, PrimitiveCustomData.EMPTY);
        boolean mirrored = (double)pose.scaleX() * pose.scaleY() * pose.scaleZ() < 0;
        float[] instance = new float[EffectMesh.Kind.MESH.floatsPerInstance()], extra = new float[40];
        pack(instance, extra, camera);
        for (int section = 0; section < geometry.sections().size(); section++) {
            var source = geometry.sections().get(section);
            if (source.indexCount() == 0 || shadow && !ParticleShadowGeometry.casts(source)) continue;
            var material = MeshDrawSelection.material(effect, fallbackMaterial, renderer, asset, entry, source, null);
            if (shadow && !ParticleShadowGeometry.casts(material)) continue;
            var batch = new MeshBatchGeometry(geometry, renderer, entry, section, true, 1, 1, false,
                    ParticleMotionSettings.DEFAULT, null, mirrored, true);
            var stream = streams.stream(material, batch, depth);
            stream.materialPrimitive(materialPrimitive);
            stream.appendInstance(instance, extra);
        }
    }

    private void pack(float[] instance, float[] extra, Vec3 camera) {
        instance[6] = instance[7] = instance[8] = instance[9] = 1;
        instance[15] = instance[16] = instance[17] = instance[18] = 1;
        instance[27] = (float)((pose.position().x - camera.x) * 100);
        instance[28] = (float)((pose.position().z - camera.z) * 100);
        instance[29] = (float)((pose.position().y - camera.y) * 100);
        instance[30] = pose.scaleX();
        instance[31] = -pose.x(); instance[32] = -pose.z(); instance[33] = -pose.y(); instance[34] = pose.w();
        extra[19] = extra[20] = extra[21] = extra[22] = 1;
        extra[28] = (float)((previous.position().x - camera.x) * 100);
        extra[29] = (float)((previous.position().z - camera.z) * 100);
        extra[30] = (float)((previous.position().y - camera.y) * 100);
        extra[31] = previous.scaleX();
        extra[32] = -previous.x(); extra[33] = -previous.z(); extra[34] = -previous.y(); extra[35] = previous.w();
        extra[36] = pose.scaleZ(); extra[37] = pose.scaleY();
        extra[38] = previous.scaleZ(); extra[39] = previous.scaleY();
    }
}
