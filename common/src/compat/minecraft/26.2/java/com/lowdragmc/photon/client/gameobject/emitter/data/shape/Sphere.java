/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.shape;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import com.kltyton.lumenfx.internal.photon.core.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.Mth;
import org.joml.Vector3f;


@LDLRegisterClient(name = "sphere", registry = "photon:shape")
public class Sphere implements IShape {

    @Getter @Setter
    @Persisted
    private float radius = .5f;
    @Getter @Setter
    @Persisted
    private float radiusThickness = 1;
    @Getter @Setter
    @Persisted
    private float arc = 360;
    @Getter
    @Persisted(subPersisted = true)
    private final ShapeArcSetting shapeArc = new ShapeArcSetting();

    @Override
    public void nextPosVel(TileParticle particle, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        var random = particle.getRandomSource();
        var outer = radius;
        var inner = (1 - radiusThickness) * radius;
        var r = outer == inner ? outer : ShapeDistribution.shellRadius(inner, outer, random.nextDouble());

        var theta = Math.acos(2 * random.nextDouble() - 1);
        var phi = arc * Mth.TWO_PI * shapeArc.sampleArcFraction(particle, emitter, random) / 360;

        var pos = new Vector3f(
                (float) (r * Math.sin(theta) * Math.cos(phi)), // X = r * sin(θ) * cos(φ)
                (float) (r * Math.cos(theta)),                  // Y = r * cos(θ) （北极到南极）
                (float) (r * Math.sin(theta) * Math.sin(phi))   // Z = r * sin(θ) * sin(φ)
        ).mul(scale);


        particle.setSimPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getSimPosWithoutNoise()), true);
        // radius 0 spawns at the center — normalize would be NaN, use zero velocity instead
        particle.setInternalVelocity(pos.lengthSquared() > 1e-12f
                ? Vector3fHelper.rotateYXY(new Vector3f(pos).normalize().mul(0.05f), rotation)
                : new Vector3f());
    }


}
