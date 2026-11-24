package com.kltyton.lumenfx.client.simulation.niagara.decal;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Floating;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.IntegerField;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererTransform;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.decal.DecalRendererSettings;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaterniond;
import org.jspecify.annotations.Nullable;

/** Niagara decals submit scene proxies from CPU particles or GPU emitters' host parameters. */
public final class NiagaraDecalParticles {
    // FRotator3f(-90, 0, 90), before conversion from source axes to Minecraft axes.
    private static final float[] DEFAULT_ORIENTATION = {-.5f, .5f, .5f, .5f};
    private final Floating position, orientation, size, color, fade;
    private final IntegerField visible, visibility, sortOrder;
    private final boolean emitter;
    private final int visibilityTag;
    private final String rendererName;
    private final Quaternionf rotation = new Quaternionf();
    private final Quaterniond worldRotation = new Quaterniond();
    private final NiagaraRendererTransform transform;

    public NiagaraDecalParticles(NiagaraDefinition.Emitter source, NiagaraDefinition.Renderer renderer) {
        var attributes = source.attributes();
        rendererName = renderer.name();
        emitter = renderer.sourceMode().equals("Emitter");
        transform = new NiagaraRendererTransform(source.localSpace());
        visibilityTag = renderer.rendererVisibility();
        position = new Floating(attributes, renderer, "Position", "Particles.Position", "Position");
        orientation = new Floating(attributes, renderer, "DecalOrientation", "Particles.DecalOrientation", "Quaternion");
        size = new Floating(attributes, renderer, "DecalSize", "Particles.DecalSize", "Vector");
        color = new Floating(attributes, renderer, "Color", "Particles.Color", "Color");
        fade = new Floating(attributes, renderer, "DecalFade", "Particles.DecalFade", "Float");
        visible = new IntegerField(attributes, renderer, "DecalVisible", "Particles.DecalVisible", true);
        sortOrder = new IntegerField(attributes, renderer, "DecalSortOrder", "Particles.UniqueID", false);
        visibility = new IntegerField(attributes, renderer.visibilityBinding(), emitter || !renderer.visibilityParticleBinding(), false);
    }

    public void append(DecalRendererSettings settings, MaterialDefinition material, @Nullable VectorDataSet particles,
                       NiagaraRendererFields.Inputs inputs, long sourcePrimitive, Vec3 origin, List<ParticleDecal> output) {
        append(settings, material, particles, inputs, sourcePrimitive, origin, Double.NaN, output);
    }

    public void append(DecalRendererSettings settings, MaterialDefinition material, @Nullable VectorDataSet particles,
                       NiagaraRendererFields.Inputs inputs, long sourcePrimitive, Vec3 origin,
                       double updateWorldSeconds, List<ParticleDecal> output) {
        if (!emitter && particles == null) throw new IllegalArgumentException("Particle decal source requires CPU simulation");
        position.defaults(0, inputs);
        orientation.defaults(DEFAULT_ORIENTATION, inputs);
        size.defaults(50, inputs);
        color.defaults(1, inputs);
        fade.defaults(1, inputs);
        int hostVisible = visible.defaultValue(-1, inputs);
        int hostVisibility = visibility.defaultValue(visibilityTag, inputs);
        int hostSortOrder = sortOrder.defaultValue(0, inputs);
        var pose = inputs.input().pose();
        transform.prepare(pose, origin.x, origin.y, origin.z);
        int count = emitter ? 1 : particles.count();
        int proxySlot = 0;
        for (int row = 0; row < count; row++) {
            if (visible.read(particles, row, hostVisible) == 0 || visibility.read(particles, row, hostVisibility) != visibilityTag) continue;
            Vec3 world = transform.position(position.read(particles, row, 0), position.read(particles, row, 1), position.read(particles, row, 2));
            rotation.set(-orientation.read(particles, row, 0), -orientation.read(particles, row, 2),
                    -orientation.read(particles, row, 1), orientation.read(particles, row, 3));
            // FQuat::GetNormalized uses identity below UE_SMALL_NUMBER, including a zero input quaternion.
            if (rotation.lengthSquared() >= 1e-8f) rotation.normalize(); else rotation.identity();
            if (!emitter && !orientation.hasParticleData()) rotation.normalize();
            transform.rotation(rotation, worldRotation);
            Vec3 halfSize = new Vec3(size.read(particles, row, 0) * .5f,
                    size.read(particles, row, 1) * .5f, size.read(particles, row, 2) * .5f);
            output.add(new ParticleDecal(material, sourcePrimitive, rendererName, proxySlot++, world, worldRotation.x, worldRotation.y, worldRotation.z, worldRotation.w,
                    halfSize, color.read(particles, row, 0), color.read(particles, row, 1), color.read(particles, row, 2),
                    color.read(particles, row, 3), fade.read(particles, row, 0), sortOrder.read(particles, row, hostSortOrder),
                    settings.screenSizeFade(), null, updateWorldSeconds));
        }
    }

    public @Nullable NiagaraBounds bounds(VectorDataSet particles) {
        if (emitter || !position.hasParticleData() || particles.count() == 0) return null;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        float sizeX = Float.NEGATIVE_INFINITY, sizeY = sizeX, sizeZ = sizeX;
        for (int row = 0; row < particles.count(); row++) {
            float x = position.read(particles, row, 0), y = position.read(particles, row, 1), z = position.read(particles, row, 2);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            if (size.hasParticleData()) {
                sizeX = Math.max(sizeX, size.read(particles, row, 0));
                sizeY = Math.max(sizeY, size.read(particles, row, 1));
                sizeZ = Math.max(sizeZ, size.read(particles, row, 2));
            }
        }
        float extent = size.hasParticleData() ? Math.max(Math.abs(sizeX), Math.max(Math.abs(sizeY), Math.abs(sizeZ))) : 0;
        return new NiagaraBounds(minX - extent, minY - extent, minZ - extent, maxX + extent, maxY + extent, maxZ + extent);
    }
}
