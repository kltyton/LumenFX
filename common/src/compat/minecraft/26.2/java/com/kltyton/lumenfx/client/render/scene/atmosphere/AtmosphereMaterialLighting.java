package com.kltyton.lumenfx.client.render.scene.atmosphere;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The two atmosphere light slots of a material view in source axes. */
public record AtmosphereMaterialLighting(Vec3 direction0, Vec3 direction1, Vec3 ground0, Vec3 ground1, boolean supported,
                                         @Nullable GpuBufferSlice distantSkyLight, @Nullable Atmosphere atmosphere) {
    private static final Vec3 UP = new Vec3(0, 0, 1);
    public static final int VIEW_INPUT = MaterialDefinition.ATMOSPHERE_LIGHT_VIEW_INPUT | MaterialDefinition.DISTANT_SKY_LIGHT_VIEW_INPUT
            | AtmosphereMaterialTextures.MASK;
    public static final int BYTES = 64;
    public static final int TEXTURE_VIEW_BYTES = 256 + AerialPerspectiveBinding.VIEW_BYTES;

    public static AtmosphereMaterialLighting resolve(@Nullable Atmosphere atmosphere, List<SceneDirectionalLight> lights,
                                                      NiagaraPlatformEnvironment platform) {
        var support = platform.console("r.SupportSkyAtmosphere");
        boolean supported = support == null || support.bits() != 0;
        if (atmosphere == null) {
            var first = SceneDirectionalLight.selectAtmosphere(lights, 0, legacyLuminance(platform));
            return new AtmosphereMaterialLighting(first == null ? UP : first.direction(), UP,
                    supported && first != null ? first.color() : Vec3.ZERO, Vec3.ZERO, supported, null, null);
        }
        var first = atmosphere.firstLight();
        var second = atmosphere.secondLight();
        var defaultDirection = atmosphere.atmosphereLightDirection();
        if (defaultDirection == null) defaultDirection = lights.isEmpty() ? UP : lights.getFirst().direction();
        return new AtmosphereMaterialLighting(first == null ? defaultDirection : first.direction(),
                second == null ? UP : second.direction(), supported && first != null ? first.groundIlluminance() : Vec3.ZERO,
                supported && second != null ? second.groundIlluminance() : Vec3.ZERO, supported,
                supported ? atmosphere.distantSkyLight() : null, supported ? atmosphere : null);
    }

    static boolean legacyLuminance(NiagaraPlatformEnvironment platform) {
        var legacy = platform.console("r.LegacyLuminanceFactors");
        return legacy != null && legacy.bits() != 0;
    }

    public void write(ByteBuffer data) {
        float support = supported ? 1f : 0f;
        writeVector(data, direction0, support);
        writeVector(data, direction1, support);
        writeVector(data, ground0, 0);
        writeVector(data, ground1, 0);
    }

    public void writeTextureView(ByteBuffer data, int inputs) {
        var active = atmosphere;
        if (active == null) {
            for (int i = 0; i < TEXTURE_VIEW_BYTES / 4; i++) data.putFloat(0f);
            return;
        }
        data.putFloat(active.translatedCenterX()).putFloat(active.translatedCenterY()).putFloat(active.translatedCenterZ())
                .putFloat(active.bottomRadiusKm());
        data.putFloat(active.topRadiusKm()).putFloat(1f).putFloat(active.reflectionCapture() ? 1f : 0f).putFloat(0f);
        var first = active.firstLight();
        var second = active.secondLight();
        writeVector(data, first == null ? Vec3.ZERO : first.outerSpaceIlluminance(), 0f);
        writeVector(data, second == null ? Vec3.ZERO : second.outerSpaceIlluminance(), 0f);
        var sky = active.skyView();
        writeVector(data, sky == null ? Vec3.ZERO : sky.forward(), sky == null ? 0f : sky.heightKm());
        writeVector(data, sky == null ? Vec3.ZERO : sky.left(), 0f);
        writeVector(data, sky == null ? Vec3.ZERO : sky.up(), 0f);
        writeVector(data, sky == null ? Vec3.ZERO : sky.luminanceFactor(), sky == null || sky.holdout() ? 0f : 1f);
        var orthographicHeight = sky == null ? null : sky.orthographicHeight();
        writeVector(data, orthographicHeight == null ? Vec3.ZERO : orthographicHeight,
                orthographicHeight == null ? 0f : 1f);
        var quantization = sky == null ? null : sky.quantization();
        writeVector(data, quantization == null ? Vec3.ZERO : quantization.error(),
                quantization == null ? 0f : quantization.frameIndexMod8());
        data.putFloat(quantization == null ? 0f : quantization.width()).putFloat(quantization == null ? 0f : quantization.height())
                .putFloat(0f).putFloat(0f);
        writeVector(data, first == null ? Vec3.ZERO : first.diskLuminance(),
                first == null ? 1f : first.diskCosHalfApex());
        writeVector(data, second == null ? Vec3.ZERO : second.diskLuminance(),
                second == null ? 1f : second.diskCosHalfApex());
        var disk = active.diskView();
        Vec3 origin = disk == null ? new Vec3(-active.translatedCenterX(), -active.translatedCenterY(),
                -active.translatedCenterZ()).normalize().scale(active.viewHeight() * .00001) : disk.origin();
        if (origin.lengthSqr() == 0) origin = UP.scale(active.viewHeight() * .00001);
        writeVector(data, origin, 0f);
        writeVector(data, disk == null ? Vec3.ZERO : disk.viewportStepX(), 0f);
        writeVector(data, disk == null ? Vec3.ZERO : disk.viewportStepY(), 0f);
        var aerial = active.aerialPerspective();
        if ((inputs & MaterialDefinition.ATMOSPHERE_AERIAL_VIEW_INPUT) != 0 && aerial != null)
            AerialPerspectiveBinding.writeView(data, aerial);
        else for (int i = 0; i < AerialPerspectiveBinding.VIEW_BYTES / 4; i++) data.putFloat(0f);
    }

    static void writeVector(ByteBuffer data, Vec3 value, float w) {
        data.putFloat((float)value.x).putFloat((float)value.y).putFloat((float)value.z).putFloat(w);
    }
}
