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

@LDLRegisterClient(name = "cone", registry = "photon:shape")
public class Cone implements IShape {

    @Getter
    @Setter
    @Persisted
    private float angle = 25;
    @Getter
    @Setter
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
        var r = outer == inner ? outer : ShapeDistribution.annulusRadius(inner, outer, random.nextDouble());

        var theta = arc * Mth.TWO_PI * shapeArc.sampleArcFraction(particle, emitter, random) / 360;

        var pos = new Vector3f((float) (r * Math.cos(theta)),
                0f,
                (float) (r *  Math.sin(theta))).mul(scale);

        var speed = new Vector3f(0, 1, 0)
                // radius 0 makes r/radius 0/0 (NaN) — treat the tilt fraction as 0 (straight up)
                .rotateAxis((float) ShapeDistribution.coneTilt(radius > 0 ? r / radius : 0, Math.toRadians(angle)), 0, 0, -1)
                .rotateAxis((float) theta, 0, -1, 0);

        particle.setSimPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getSimPosWithoutNoise()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(speed.normalize().mul(0.05f), rotation));
    }

}
