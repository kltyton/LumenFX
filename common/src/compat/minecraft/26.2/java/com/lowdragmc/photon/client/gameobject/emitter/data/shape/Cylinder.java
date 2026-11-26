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


@LDLRegisterClient(name = "cylinder", registry = "photon:shape")
public class Cylinder implements IShape {

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
                random.nextFloat() * 1 - 0.5f,
                (float) (r * Math.sin(theta))).mul(scale);
        var radialDirection = new Vector3f((float) Math.cos(theta), 0, (float) Math.sin(theta));

        particle.setSimPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getSimPosWithoutNoise()), true);
        particle.setInternalVelocity(Vector3fHelper.rotateYXY(radialDirection.normalize().mul(0.05f), rotation));
    }

}
