package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.client.render.EffectMesh;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.niagara.sort.NiagaraIndexSort;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.mesh.MeshBatchGeometry;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Packs completed CPU Niagara datasets without touching simulation or device state. */
public final class MeshInstanceBuilder {
    public interface Streams {
        EffectMesh stream(MaterialDefinition material, MeshBatchGeometry geometry, double depth);
    }
    private int[] order = new int[256], nativeSortKeys = new int[256];
    private final NiagaraIndexSort niagaraSort = new NiagaraIndexSort();

    public void append(EffectDefinition effect, EmitterInstance.Snapshot snapshot, Vec3 camera, MeshView meshView, Streams streams) {
        double cameraX = camera.x, cameraY = camera.y, cameraZ = camera.z;
        if (meshView == null) throw new IllegalStateException("Mesh rendering requires a captured projection view");
        var definition = snapshot.definition();
        meshView = meshView.withLodPolicy(effect.materials().get(definition.material()).renderSettings().meshLod());
        var particles = java.util.Objects.requireNonNull(snapshot.nativeParticles());
        var settings = java.util.Objects.requireNonNull(definition.meshSettings());
        boolean rendererTranslucent = MeshDrawSelection.translucent(effect, definition, snapshot.input());
        var input = snapshot.input();
        var pose = input.pose();
        if (!particles.emitterDistanceVisible()) return;
        var ownerRotation = new Quaternionf(-pose.x(), -pose.z(), -pose.y(), pose.w());
        float[] instance = new float[EffectMesh.Kind.MESH.floatsPerInstance()], extra = new float[40];
        var entries = MeshDrawSelection.entries(settings, input);
        var hostMeshIndex = particles.hostMeshIndex();
        int nextDrawIndex = 0;
        for (int meshIndex = 0; meshIndex < entries.size(); meshIndex++) {
            var entry = MeshDrawSelection.entry(entries.get(meshIndex), input, settings);
            var asset = entry.asset();
            if (asset == null) continue;
            boolean perParticleLod = entry.lodMode() == MeshRendererSettings.LodMode.PER_PARTICLE;
            var lodRange = MeshLodSelection.particleRange(asset, entry, meshView);
            int firstDrawIndex = nextDrawIndex;
            nextDrawIndex += perParticleLod ? lodRange.drawCount() : 1;
            if (!MeshDrawSelection.selectsDraw(hostMeshIndex, firstDrawIndex)) continue;
            com.kltyton.lumenfx.client.render.mesh.scene.MeshEmitterSceneData emitterScene = null;
            if (com.kltyton.lumenfx.client.render.mesh.scene.MeshEmitterSceneData.applies(settings, particles.emitterSource())) {
                float[] currentPosition = new float[3], previousPosition = new float[3], currentScale = new float[3], previousScale = new float[3];
                float[] currentRotation = new float[4], previousRotation = new float[4];
                for (int axis = 0; axis < 3; axis++) {
                    currentPosition[axis] = particles.meshDefaultPosition(false, axis);
                    previousPosition[axis] = particles.meshDefaultPosition(true, axis);
                    currentScale[axis] = particles.meshScale(0, axis); previousScale[axis] = particles.previousScale(0, axis);
                }
                for (int axis = 0; axis < 4; axis++) {
                    currentRotation[axis] = particles.meshOrientation(0, axis); previousRotation[axis] = particles.previousOrientation(0, axis);
                }
                emitterScene = com.kltyton.lumenfx.client.render.mesh.scene.MeshEmitterSceneData.prepare(entry, definition.localSpace(),
                        particles.motion().precise(), currentPosition, currentRotation, currentScale, previousPosition, previousRotation, previousScale,
                        particles.meshSimulationOrigin(), pose, particles.previousOwnerPose(), camera);
            }
            int selectedLod = MeshDrawSelection.lod(asset, entry, settings, meshView, input, camera,
                    particles::meshInteger, particles::componentBounds);
            var resident = asset.resident();
            int count = particles.count();
            if (order.length < count) {
                order = new int[Math.max(count, order.length * 2)];
                nativeSortKeys = new int[order.length];
            }
            int visible = 0;
            for (int row = 0; row < count; row++) {
                if (particles.selectsMeshParticle(row, meshIndex)) {
                    order[visible++] = row;
                }
            }
            if (visible == 0) continue;
            sortMeshParticles(snapshot, rendererTranslucent, visible, camera, meshView);
            // UE's CPU sort/cull handles particle tags. The first draw of a per-particle LOD slot is its CPU path.
            int lod = Math.max(0, perParticleLod ? lodRange.first() : selectedLod);
            if (lod < asset.lods().size()) {
                int available = asset.firstResidentLod(Math.max(lod, asset.sampling().firstAvailableLod()), resident);
                var geometry = asset.residentGeometry(available, resident);
                if (geometry == null) continue;
                for (int section = 0; section < geometry.sections().size(); section++) {
                    var sourceSection = geometry.sections().get(section);
                    if (sourceSection.indexCount() == 0 || meshView.shadowPass() && !ParticleShadowGeometry.casts(sourceSection)) continue;
                    var material = particles.material(MeshDrawSelection.material(effect, definition, asset, entry, sourceSection, input));
                    if (meshView.shadowPass() && !ParticleShadowGeometry.casts(material)) continue;
                    var resolved = new MeshBatchGeometry(geometry, settings, entry, section, definition.localSpace(),
                            particles.subImageColumns(), particles.subImageRows(), particles.subImageBlend(), particles.motion());
                    var componentBounds = particles.componentBounds();
                    double depth = MeshDrawSelection.depth(pose, componentBounds, camera, meshView);
                    var stream = streams.stream(material, resolved, depth);
                    stream.emitterScene(emitterScene);
                    for (int i = 0; i < visible; i++) {
                        int row = order[i];
                        instance[0] = (float)((particles.x(row) - (definition.localSpace() ? 0 : cameraX)) * 100);
                        instance[1] = (float)((particles.z(row) - (definition.localSpace() ? 0 : cameraZ)) * 100);
                        instance[2] = (float)((particles.y(row) - (definition.localSpace() ? 0 : cameraY)) * 100);
                        for (int axis = 0; axis < 4; axis++) instance[3 + axis] = particles.meshOrientation(row, axis);
                        for (int axis = 0; axis < 3; axis++) { instance[7 + axis] = particles.meshScale(row, axis); instance[11 + axis] = particles.velocity(row, axis); }
                        instance[10] = particles.cameraOffset(row); instance[14] = 0;
                        instance[15] = particles.color(row, 0) * input.red(); instance[16] = particles.color(row, 1) * input.green();
                        instance[17] = particles.color(row, 2) * input.blue(); instance[18] = particles.color(row, 3) * input.alpha();
                        for (int channel = 0; channel < 4; channel++) instance[19 + channel] = particles.dynamicParameter(row, channel,
                                (float)definition.customData().get(channel).sample(particles.normalizedAge(row), particles.random(row)));
                        instance[23] = particles.normalizedAge(row); instance[24] = particles.random(row);
                        instance[25] = particles.subImage(row); instance[26] = particles.dynamicParameterMask();
                        instance[27] = (float)((pose.position().x - cameraX) * 100);
                        instance[28] = (float)((pose.position().z - cameraZ) * 100);
                        instance[29] = (float)((pose.position().y - cameraY) * 100); instance[30] = pose.scale();
                        instance[31] = ownerRotation.x; instance[32] = ownerRotation.y; instance[33] = ownerRotation.z; instance[34] = ownerRotation.w;
                        if ((material.dynamicSlots() & 14) != 0)
                            for (int slot = 1; slot < 4; slot++) for (int channel = 0; channel < 4; channel++)
                                extra[(slot - 1) * 4 + channel] = particles.dynamicParameter(row, slot, channel);
                        for (int axis = 0; axis < 3; axis++) {
                            double cameraCoordinate = axis == 0 ? cameraX : axis == 1 ? cameraZ : cameraY;
                            extra[12 + axis] = (float)(particles.previousPosition(row, axis) - (definition.localSpace() ? 0 : cameraCoordinate * 100));
                            extra[20 + axis] = particles.previousScale(row, axis);
                            extra[24 + axis] = particles.previousVelocity(row, axis);
                        }
                        extra[15] = extra[27] = 0;
                        for (int axis = 0; axis < 4; axis++) extra[16 + axis] = particles.previousOrientation(row, axis);
                        extra[23] = particles.previousCameraOffset(row);
                        var previousPose = particles.previousOwnerPose();
                        extra[28] = (float)((previousPose.position().x - cameraX) * 100);
                        extra[29] = (float)((previousPose.position().z - cameraZ) * 100);
                        extra[30] = (float)((previousPose.position().y - cameraY) * 100); extra[31] = previousPose.scale();
                        extra[32] = -previousPose.x(); extra[33] = -previousPose.z(); extra[34] = -previousPose.y(); extra[35] = previousPose.w();
                        extra[36] = pose.scaleZ(); extra[37] = pose.scaleY();
                        extra[38] = previousPose.scaleZ(); extra[39] = previousPose.scaleY();
                        stream.appendInstance(instance, extra);
                    }
                }
            }
        }
    }

    private void sortMeshParticles(EmitterInstance.Snapshot snapshot, boolean translucent, int count, Vec3 camera, MeshView view) {
        var particles = snapshot.nativeParticles();
        var mode = snapshot.definition().sortMode();
        if ((!translucent && particles.sortOnlyWhenTranslucent()) || !particles.supportsSort(mode)) return;
        boolean sortByViewDepth = mode == EmitterDefinition.SortMode.VIEW_DEPTH;
        boolean custom = mode == EmitterDefinition.SortMode.CUSTOM_ASCENDING || mode == EmitterDefinition.SortMode.CUSTOM_DESCENDING;
        var pose = snapshot.input().pose();
        var localCamera = new org.joml.Vector3d(camera.x, camera.y, camera.z);
        if (snapshot.definition().localSpace()) {
            var owner = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.minecraft(pose, true, new org.joml.Matrix4d());
            com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverse(owner, owner).transformPosition(localCamera);
        }
        for (int index = 0; index < count; index++) {
            int row = order[index];
            if (custom) nativeSortKeys[row] = particles.customSortKey(row, mode == EmitterDefinition.SortMode.CUSTOM_ASCENDING);
            else {
                Vec3 position = new Vec3(particles.x(row), particles.y(row), particles.z(row));
                double distance;
                if (sortByViewDepth) {
                    if (snapshot.definition().localSpace()) position = pose.transformPosition(position);
                    distance = view.depth(position.subtract(camera)) * 100;
                } else {
                    double dx = position.x - localCamera.x, dy = position.y - localCamera.y, dz = position.z - localCamera.z;
                    distance = (dx * dx + dy * dy + dz * dz) * 10000;
                }
                nativeSortKeys[row] = NiagaraIndexSort.viewKey((float) distance);
            }
        }
        niagaraSort.sortParticles(order, count, nativeSortKeys, NiagaraPlatforms.snapshot().radixSortThreshold());
    }

}
