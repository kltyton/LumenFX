package com.kltyton.lumenfx.client.audio.scene;

import com.kltyton.lumenfx.client.simulation.niagara.curve.NativeCurveSampler;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Attenuation;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Falloff;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundAcoustics.Shape;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Source distance gain evaluated against the current listener and voice pose. */
public final class NiagaraAudioAttenuation {
    private final Attenuation settings;
    private final @Nullable NativeCurveSampler curve;
    private final int[] input = new int[1], output = new int[1];

    public NiagaraAudioAttenuation(Attenuation settings) {
        this.settings = settings;
        var source = settings.customCurve();
        curve = source == null ? null : new NativeCurveSampler(source);
    }

    public float gain(Vec3 origin, Vec3 rotation, Vec3 listener) {
        return gain(origin, rotation, listener, 1);
    }

    public float gain(Vec3 origin, Vec3 rotation, Vec3 listener, float distanceScale) {
        if (!settings.attenuate()) return 1;
        Vec3 relative = listener.subtract(origin);
        Vec3 extent = settings.extents();
        if (settings.shape() == Shape.Sphere)
            return falloff(Math.max(relative.length() - extent.x, 0) * distanceScale, settings.falloff());
        var axes = new Axes(rotation);
        return switch (settings.shape()) {
            case Capsule -> {
                double segment = Math.max(0, extent.x - extent.y);
                double distance = segment == 0 ? Math.max(relative.length() - extent.y, 0)
                        : relative.subtract(axes.z().scale(Math.clamp(relative.dot(axes.z()), -segment, segment))).length() - extent.y;
                yield falloff(distance * distanceScale, settings.falloff());
            }
            case Box -> {
                double x = Math.max(0, Math.abs(relative.dot(axes.x())) - extent.x);
                double y = Math.max(0, Math.abs(relative.dot(axes.y())) - extent.y);
                double z = Math.max(0, Math.abs(relative.dot(axes.z())) - extent.z);
                double squared = x * x + y * y + z * z;
                yield squared <= 1.e-8 || squared < settings.falloff() * settings.falloff()
                        ? falloff(Math.sqrt(squared) * distanceScale, settings.falloff()) : 0;
            }
            case Cone -> {
                Vec3 direction = relative.add(axes.x().scale(settings.coneOffset()));
                double distance = direction.length();
                float sphere = Math.abs(settings.coneSphereRadius()) <= 1.e-8f ? 0
                        : falloff(Math.max(distance - settings.coneSphereRadius(), 0) * distanceScale, settings.coneSphereFalloff());
                if (settings.coneSphereRadius() >= extent.x) yield sphere;
                float gain = falloff(Math.max(distance - extent.x, 0) * distanceScale, settings.falloff());
                if (gain > 0) {
                    double cosine = axes.x().dot(direction.normalize());
                    double degrees = Math.toDegrees(Math.acos(Math.clamp(cosine, -1, 1)));
                    gain *= falloff(degrees - extent.y, extent.z);
                }
                yield Math.max(sphere, gain);
            }
            case Sphere -> throw new IllegalStateException("Spherical attenuation already evaluated");
        };
    }

    private float falloff(double distance, double falloff) {
        float normalized = (float)distance / Math.max((float)falloff, 1);
        float result = switch (settings.distance()) {
            case Linear -> 1 - normalized;
            case Logarithmic -> -.5f * (float)Math.log(Math.max((float)distance, 1.e-4f) / Math.max((float)falloff, 1));
            case Inverse -> .02f / (Math.max((float)distance, 1.e-4f) / Math.max((float)falloff, 1));
            case LogReverse -> normalized > 1 ? 0 : 1 + .5f * (float)Math.log(Math.max(1 - normalized, 1.e-4f));
            case NaturalSound -> {
                float alpha = settings.mode() == Falloff.Continues ? normalized : Math.clamp(normalized, 0, 1);
                yield settings.mode() == Falloff.Silent && alpha >= 1 ? 0
                        : (float)Math.pow(10, alpha * settings.decibels() / 20f);
            }
            case Custom -> {
                input[0] = Float.floatToRawIntBits(normalized);
                Objects.requireNonNull(curve).sample(1, input, output);
                yield Float.intBitsToFloat(output[0]);
            }
        };
        return Math.clamp(result, 0, 1);
    }

    private record Axes(Vec3 x, Vec3 y, Vec3 z) {
        Axes(Vec3 rotator) {
            this(Math.toRadians(rotator.x), Math.toRadians(rotator.y), Math.toRadians(rotator.z));
        }
        Axes(double pitch, double yaw, double roll) {
            this(Math.cos(pitch), Math.sin(pitch), Math.cos(yaw), Math.sin(yaw), Math.cos(roll), Math.sin(roll));
        }
        Axes(double cp, double sp, double cy, double sy, double cr, double sr) {
            this(new Vec3(cp * cy, cp * sy, sp),
                    new Vec3(sr * sp * cy - cr * sy, sr * sp * sy + cr * cy, -sr * cp),
                    new Vec3(-cr * sp * cy - sr * sy, cy * sr - cr * sp * sy, cr * cp));
        }
    }
}
