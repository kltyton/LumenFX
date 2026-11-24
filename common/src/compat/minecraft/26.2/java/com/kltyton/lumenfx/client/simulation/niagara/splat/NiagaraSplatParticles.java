package com.kltyton.lumenfx.client.simulation.niagara.splat;

import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Floating;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Inputs;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.IntegerField;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import org.jspecify.annotations.Nullable;

/** Resolves UNiagaraSplatRendererProperties bindings, host defaults, and dynamic bounds. */
public final class NiagaraSplatParticles {
    private static final float SPLAT_RADIUS_SIGMAS = 2.0f;

    public record Registers(int position, int scale, int rotation, int color, int visibilityTag) { }

    public record Defaults(float[] position, float[] scale, float[] rotation, float[] color, int visibilityTag) { }

    private final Floating position, scale, rotation, color;
    private final IntegerField visibility;
    private final boolean emitterSource, localSpace;
    private final int rendererVisibility;
    private final Registers registers;

    public NiagaraSplatParticles(NiagaraDefinition.Emitter source, NiagaraDefinition.Renderer renderer) {
        var attributes = source.attributes();
        emitterSource = renderer.sourceMode().equals("Emitter");
        localSpace = source.localSpace();
        rendererVisibility = renderer.rendererVisibility();
        position = new Floating(attributes, renderer, "Position", "Particles.Position", "Position");
        scale = new Floating(attributes, renderer, "Scale", "Particles.Scale", "Vector");
        rotation = new Floating(attributes, renderer, "Rotation", "Particles.MeshOrientation", "Quaternion");
        color = new Floating(attributes, renderer, "Color", "Particles.Color", "Color");
        var visibilityAttr = renderer.attributeBindings().get("RendererVisibilityTag");
        visibility = visibilityAttr != null
                ? new IntegerField(attributes, renderer, "RendererVisibilityTag", renderer.visibilityBinding(), false)
                : new IntegerField(attributes, renderer.visibilityBinding(), emitterSource || !renderer.visibilityParticleBinding(), false);
        registers = new Registers(position.registerIndex(), scale.registerIndex(), rotation.registerIndex(),
                color.registerIndex(), visibility.registerIndex());
    }

    public boolean emitterSource() { return emitterSource; }
    public boolean localSpace() { return localSpace; }
    public int rendererVisibility() { return rendererVisibility; }
    public Registers registers() { return registers; }

    public Defaults resolveDefaults(Inputs inputs, double originX, double originY, double originZ) {
        float[] pos = new float[3];
        if (!localSpace) {
            var owner = inputs.input().pose().position();
            pos[0] = (float) (owner.x * 100.0 - originX);
            pos[1] = (float) (owner.z * 100.0 - originY);
            pos[2] = (float) (owner.y * 100.0 - originZ);
        }
        position.defaults(pos, inputs);
        position.copyDefaults(pos);

        float[] scl = {1f, 1f, 1f};
        scale.defaults(scl, inputs);
        scale.copyDefaults(scl);

        float[] rot = {0f, 0f, 0f, 1f};
        rotation.defaults(rot, inputs);
        rotation.copyDefaults(rot);

        float[] col = {1f, 1f, 1f, 1f};
        color.defaults(col, inputs);
        color.copyDefaults(col);

        int vis = visibility.defaultValue(rendererVisibility, inputs);
        return new Defaults(pos, scl, rot, col, vis);
    }

    public @Nullable NiagaraBounds bounds(VectorDataSet particles) {
        if (emitterSource || !position.hasParticleData() || particles.count() == 0) return null;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        float maxScale = 0f;
        for (int row = 0; row < particles.count(); row++) {
            float x = position.read(particles, row, 0), y = position.read(particles, row, 1), z = position.read(particles, row, 2);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            if (scale.hasParticleData()) {
                maxScale = Math.max(maxScale, Math.abs(scale.read(particles, row, 0)));
                maxScale = Math.max(maxScale, Math.abs(scale.read(particles, row, 1)));
                maxScale = Math.max(maxScale, Math.abs(scale.read(particles, row, 2)));
            }
        }
        double pad = scale.hasParticleData() ? maxScale * SPLAT_RADIUS_SIGMAS : SPLAT_RADIUS_SIGMAS;
        return new NiagaraBounds(minX - pad, minY - pad, minZ - pad, maxX + pad, maxY + pad, maxZ + pad);
    }
}
