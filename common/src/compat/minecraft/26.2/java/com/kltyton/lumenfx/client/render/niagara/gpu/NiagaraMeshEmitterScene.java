package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.mesh.scene.MeshEmitterSceneData;
import net.minecraft.world.phys.Vec3;

/** Freezes emitter-mode host defaults through the same resolver used by vertex uniforms. */
final class NiagaraMeshEmitterScene {
    private NiagaraMeshEmitterScene() { }

    static MeshEmitterSceneData prepare(NiagaraGpuMeshCollector.Task task) {
        var pose = task.input().pose();
        float[] position = task.emitter().localSpace() ? new float[3] : new float[]{
                (float)(pose.position().x * 100 - task.originX()),
                (float)(pose.position().z * 100 - task.originY()), (float)(pose.position().y * 100 - task.originZ())};
        float[] rotation = {0, 0, 0, 1}, scale = {1, 1, 1};
        host(task, "Position", position); host(task, "MeshOrientation", rotation); host(task, "Scale", scale);
        float[] oldPosition = position.clone(), oldRotation = rotation.clone(), oldScale = scale.clone();
        host(task, "PreviousPosition", oldPosition); host(task, "PreviousMeshOrientation", oldRotation); host(task, "PreviousScale", oldScale);
        return MeshEmitterSceneData.prepare(task.geometry().mesh(), task.emitter().localSpace(), task.renderer().motion().precise(),
                position, rotation, scale, oldPosition, oldRotation, oldScale,
                new Vec3(task.originX(), task.originY(), task.originZ()), pose, task.previousPose(), task.camera());
    }

    private static void host(NiagaraGpuMeshCollector.Task task, String semantic, float[] values) {
        NiagaraGpuSpriteUniforms.host(task.renderer(), semantic, task.system(), task.variables(), task.input(), values);
    }
}
