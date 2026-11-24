package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.client.render.light.shadow.contact.ContactShadowControls;
import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Source-world component data supplements the shared three-vector simple-light upload. */
public final class PointLightUniforms {
    public static final int BYTES = 208;
    private final Matrix4f viewToSourceWorld;
    private final Vec3 camera;
    private final ContactShadowControls contact;

    public PointLightUniforms(Matrix4fc viewToSourceWorld, Vec3 camera) {
        this(viewToSourceWorld, camera, ContactShadowControls.DEFAULT);
    }
    public PointLightUniforms(Matrix4fc viewToSourceWorld, Vec3 camera, ContactShadowControls contact) {
        this.viewToSourceWorld = new Matrix4f(viewToSourceWorld);
        this.camera = camera;
        this.contact = contact;
    }

    public void write(ByteBuffer data, ScenePointLight light, int baseLightIndex, int iesIndex, int lightFunctionIndex) {
        write(data, light, baseLightIndex, iesIndex, lightFunctionIndex, false);
    }
    public void write(ByteBuffer data, ScenePointLight light, int baseLightIndex, int iesIndex, int lightFunctionIndex,
                      boolean lightFunctionAtlas) {
        var shader = light.data().shader();
        int start = data.position();
        viewToSourceWorld.get(start, data);
        data.position(start + 64);
        Vec3 position = shader.worldPosition();
        data.putFloat((float)(position.x - camera.x * 100)).putFloat((float)(position.y - camera.z * 100))
                .putFloat((float)(position.z - camera.y * 100)).putFloat(shader.inverseSquared() ? 1 : 0);
        Vec3 direction = shader.direction(), tangent = shader.tangent();
        data.putFloat((float)direction.x).putFloat((float)direction.y).putFloat((float)direction.z).putFloat(shader.sourceRadius());
        data.putFloat((float)tangent.x).putFloat((float)tangent.y).putFloat((float)tangent.z).putFloat(shader.sourceLength());
        data.putFloat(shader.softSourceRadius()).putFloat(baseLightIndex).putFloat(iesIndex).putFloat(lightFunctionIndex);
        var settings = contact.effective(light);
        data.putFloat(settings.encodedLength()).putFloat(settings.castingIntensity()).putFloat(settings.nonCastingIntensity())
                .putFloat(contact.shadowedBits(light, lightFunctionAtlas));
        data.putInt(light.data().lightingChannels()).putInt(light.data().transmitsThroughSurfaces() ? 1 : 0).putInt(0).putInt(0);
        var cone = shader.cone();
        data.putFloat(cone == null ? -1f : cone.cosineOuter()).putFloat(cone == null ? 0f : cone.inverseCosineDifference())
                .putFloat(0).putFloat(0);
        var rect = shader.rect();
        if (rect == null) for (int word = 0; word < 4; word++) data.putLong(0L);
        else {
            data.putFloat(rect.halfWidth()).putFloat(rect.halfHeight()).putFloat(rect.barnCosine()).putFloat(rect.barnLength());
            data.putFloat(rect.scaleX()).putFloat(rect.scaleY()).putFloat(rect.offsetX()).putFloat(rect.offsetY());
        }
    }
}
