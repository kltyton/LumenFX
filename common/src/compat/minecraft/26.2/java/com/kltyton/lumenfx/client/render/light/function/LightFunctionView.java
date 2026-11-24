package com.kltyton.lumenfx.client.render.light.function;

import com.kltyton.lumenfx.client.render.precision.WorldOriginUniforms;
import com.kltyton.lumenfx.effect.render.light.scene.LightFunctionSettings;
import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Frozen source-axis pixel transforms for the deferred light-function material pass. */
public final class LightFunctionView {
    public static final int OBJECT_POSITION_BYTES = WorldOriginUniforms.BYTES + 16;
    public static final int BYTES = 272 + OBJECT_POSITION_BYTES;
    private final Matrix4f pixelToLight, pixelToWorld, pixelToView;
    private final LightFunctionSettings settings;
    private final Vec3 cameraOrigin, viewForward;
    private final Vec3 lightOrigin, lightViewOrigin;
    private final int width, height, framebufferHeight;
    private final float tanOuterCone, shadowFade;
    private final boolean flipY, spot, point, preview, orthographic;

    public LightFunctionView(Matrix4dc clipToWorld, Matrix4dc clipToTranslatedWorld, Matrix4dc clipToView,
                             Matrix4dc worldToLight, LightFunctionSettings settings, int viewX, int viewY, int width, int height,
                             int framebufferHeight, boolean flipY, float tanOuterCone, float shadowFade,
                             boolean spot, boolean point, boolean preview, boolean orthographic, Vec3 cameraOrigin, Vec3 viewForward,
                             Vec3 lightOrigin, Vec3 lightViewOrigin) {
        if (width <= 0 || height <= 0 || framebufferHeight <= 0) throw new IllegalArgumentException("Invalid light-function viewport");
        Matrix4d pixelToClip = new Matrix4d().m00(2.0 / width).m11(-2.0 / height)
                .m30(-1.0 - 2.0 * viewX / width).m31(1.0 + 2.0 * viewY / height);
        Vec3 scale = settings.scale();
        Matrix4d scaledWorldToLight = new Matrix4d().scaling(1.0 / scale.z, 1.0 / scale.y, 1.0 / scale.x).mul(worldToLight);
        pixelToLight = new Matrix4f(scaledWorldToLight.mul(new Matrix4d(clipToWorld).mul(pixelToClip)));
        pixelToWorld = new Matrix4f(new Matrix4d(clipToTranslatedWorld).mul(pixelToClip));
        pixelToView = new Matrix4f(new Matrix4d(clipToView).mul(pixelToClip));
        this.settings = settings; this.width = width; this.height = height; this.framebufferHeight = framebufferHeight;
        this.flipY = flipY; this.tanOuterCone = tanOuterCone; this.shadowFade = shadowFade;
        this.spot = spot; this.point = point; this.preview = preview; this.orthographic = orthographic;
        this.cameraOrigin = cameraOrigin; this.viewForward = viewForward;
        this.lightOrigin = lightOrigin; this.lightViewOrigin = lightViewOrigin;
    }

    public void write(ByteBuffer data) {
        int start = data.position();
        pixelToLight.get(start, data); pixelToWorld.get(start + 64, data); pixelToView.get(start + 128, data);
        data.position(start + 192);
        data.putFloat(tanOuterCone).putFloat(shadowFade).putFloat(spot ? 1 : 0).putFloat(point ? 1 : 0);
        data.putFloat(settings.fadeDistance()).putFloat(settings.disabledBrightness()).putFloat(preview ? 1 : 0).putFloat(orthographic ? 1 : 0);
        data.putFloat(1f / width).putFloat(1f / height).putFloat(flipY ? 1 : 0).putFloat(framebufferHeight);
        data.putFloat((float)cameraOrigin.x).putFloat((float)cameraOrigin.y).putFloat((float)cameraOrigin.z).putFloat(0);
        data.putFloat((float)viewForward.x).putFloat((float)viewForward.y).putFloat((float)viewForward.z).putFloat(0);
        writeObjectPosition(data, lightOrigin, lightViewOrigin);
    }

    public static Vec3 sourceViewOrigin(Vec3 camera, Matrix4fc view) {
        var inverse = new Matrix4d(view).invert();
        return new Vec3((camera.x + inverse.m30()) * 100, (camera.z + inverse.m32()) * 100,
                (camera.y + inverse.m31()) * 100);
    }

    public static void writeObjectPosition(ByteBuffer data, Vec3 lightOrigin, Vec3 sourceViewOrigin) {
        WorldOriginUniforms.writeSourcePosition(data, lightOrigin.x, lightOrigin.y, lightOrigin.z);
        var relative = lightOrigin.subtract(sourceViewOrigin);
        data.putFloat((float)relative.x).putFloat((float)relative.y).putFloat((float)relative.z).putFloat(0);
    }
}
