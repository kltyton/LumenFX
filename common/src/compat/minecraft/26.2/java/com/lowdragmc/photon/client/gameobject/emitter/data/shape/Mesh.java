/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.shape;
import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.internal.photon.core.utils.Vector3fHelper;
import com.lowdragmc.photon.client.gameobject.emitter.IParticleEmitter;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;


@LDLRegisterClient(name = "mesh", registry = "photon:shape")
public class Mesh implements IShape {
    public enum Type {
        Vertex,
        Edge,
        Triangle
    }

    @Getter
    @Setter
    @Persisted
    private Type type = Type.Triangle;

    @Getter
    @Setter
    @Persisted
    private MeshData meshData = new MeshData();

    @Override
    public void nextPosVel(TileParticle particle, IParticleEmitter emitter, Vector3f position, Vector3f rotation, Vector3f scale) {
        Vector3f pos = null;
        var random = particle.getRandomSource();
        var t = random.nextFloat();
        var meshData = getMeshData();
        if (type == Type.Vertex) {
            pos = meshData.getRandomVertex(t);
            if (pos != null) {
                pos = new Vector3f(pos);
            }
        } else if (type == Type.Edge) {
            var edge = meshData.getRandomEdge(t);
            if (edge != null) {
                pos = new Vector3f(edge.b).sub(edge.a).mul(random.nextFloat()).add(edge.a);
            }
        } else if (type == Type.Triangle) {
            var triangle = meshData.getRandomTriangle(t);
            if (triangle != null) {
                var sqrtR = (float) ShapeDistribution.triangleEdgeWeight(random.nextFloat());
                var A = (1 - sqrtR);
                var r2 = random.nextFloat();
                var B = (sqrtR * (1 - r2));
                var C = (sqrtR * r2);
                var x = A * triangle.a.x + B * triangle.b.x + C * triangle.c.x;
                var y = A * triangle.a.y + B * triangle.b.y + C * triangle.c.y;
                var z = A * triangle.a.z + B * triangle.b.z + C * triangle.c.z;
                pos = new Vector3f(x, y, z);
            }
        }
        if (pos != null) {
            pos.mul(scale);
            particle.setSimPos(Vector3fHelper.rotateYXY(new Vector3f(pos), rotation).add(position).add(particle.getSimPosWithoutNoise()), true);
            particle.setInternalVelocity(new Vector3f(0, 0, 0));
        }
    }

}
