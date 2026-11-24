package com.kltyton.lumenfx.client.simulation.niagara.component;

import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.component.ComponentValue.Kind;
import static com.kltyton.lumenfx.client.render.math.SourceColorMath.unorm8;
import net.minecraft.world.phys.Vec3;

/** CPU component bindings use user-facing structs after simulation-space LWC conversion. */
final class ComponentValueConversion {
    private ComponentValueConversion() { }

    static boolean widens(Kind source, Kind target) {
        return source == Kind.FLOAT && target == Kind.DOUBLE
                || source == Kind.FLOAT2 && target == Kind.DOUBLE2
                || source == Kind.FLOAT3 && target == Kind.DOUBLE3
                || source == Kind.FLOAT4 && target == Kind.DOUBLE4
                || source == Kind.QUAT_FLOAT && target == Kind.QUAT_DOUBLE;
    }

    static ComponentValue convert(ComponentValue value, Kind target, Vec3 simulationOrigin) {
        Kind source = value.kind();
        if (source == target) return value;
        float x = (float)value.number(0), y = source.components() > 1 ? (float)value.number(1) : 0;
        float z = source.components() > 2 ? (float)value.number(2) : 0;
        float w = source.components() > 3 ? (float)value.number(3) : 0;
        if (target == Kind.BYTE_COLOR) {
            if (source == Kind.LINEAR_COLOR)
                return ComponentValue.color(unorm8(x), unorm8(y), unorm8(z), unorm8(w));
            if (source == Kind.FLOAT3 || source == Kind.FLOAT4)
                return ComponentValue.color(truncateColor(x), truncateColor(y), truncateColor(z), source == Kind.FLOAT3 ? 255 : truncateColor(w));
        }
        if (target == Kind.ROTATOR) {
            if (source == Kind.FLOAT3) return ComponentValue.doubles(target, x, y, z, 0);
            if (source == Kind.QUAT_FLOAT) return rotation(x, y, z, w);
        }
        if (source == Kind.POSITION && (target == Kind.DOUBLE3 || target == Kind.FLOAT3)) {
            double px = simulationOrigin.x + x, py = simulationOrigin.y + y, pz = simulationOrigin.z + z;
            return target == Kind.DOUBLE3 ? ComponentValue.doubles(target, px, py, pz, 0)
                    : ComponentValue.floating(target, (float)px, (float)py, (float)pz, 0);
        }
        if (widens(source, target))
            return ComponentValue.doubles(target, x, y, z, w);
        throw new IllegalArgumentException("Unsupported component property conversion " + source + " -> " + target);
    }

    private static int truncateColor(float value) {
        float scaled = value * 255f;
        int truncated = !Float.isFinite(scaled) || scaled >= 0x1.0p31f || scaled < -0x1.0p31f
                ? Integer.MIN_VALUE : (int)scaled;
        return Math.max(0, Math.min(truncated, 255));
    }

    private static ComponentValue rotation(float x, float y, float z, float w) {
        float singularity = z * x - w * y;
        float yawY = 2f * (w * z + x * y), yawX = 1f - 2f * (y * y + z * z);
        float degrees = 180f / (float)Math.PI;
        float pitch, yaw, roll;
        if (singularity < -.4999995f) {
            pitch = -90; yaw = normalizeAxis(-2f * atan2(x, w) * degrees); roll = 0;
        } else if (singularity > .4999995f) {
            pitch = 90; yaw = normalizeAxis(2f * atan2(x, w) * degrees); roll = 0;
        } else {
            pitch = fastAsin(2f * singularity) * degrees;
            yaw = atan2(yawY, yawX) * degrees;
            roll = atan2(-2f * (w * x + y * z), 1f - 2f * (x * x + y * y)) * degrees;
        }
        return ComponentValue.doubles(Kind.ROTATOR, pitch, yaw, roll, 0);
    }

    private static float normalizeAxis(float angle) {
        angle %= 360f;
        if (angle < 0) angle += 360f;
        if (angle > 180f) angle -= 360f;
        return angle;
    }

    private static float fastAsin(float value) {
        float x = Math.abs(value), remainder = 1f - x;
        if (remainder < 0) remainder = 0;
        float result = ((((((-.0012624911f * x + .0066700901f) * x - .0170881256f) * x
                + .0308918810f) * x - .0501743046f) * x + .0889789874f) * x - .2145988016f) * x + 1.5707963050f;
        result *= (float)Math.sqrt(remainder);
        return value >= 0 ? 1.5707963050f - result : result - 1.5707963050f;
    }

    private static float atan2(float y, float x) {
        float ax = Math.abs(x), ay = Math.abs(y);
        boolean largerY = ay > ax;
        float maximum = largerY ? ay : ax, minimum = largerY ? ax : ay;
        if (maximum == 0) return 0;
        float ratio = minimum / maximum, square = ratio * ratio;
        float polynomial = .0072128853633444123f;
        polynomial = polynomial * square - .035059680836411644f;
        polynomial = polynomial * square + .08167588285994043f;
        polynomial = polynomial * square - .13374657325451267f;
        polynomial = polynomial * square + .19856563505717162f;
        polynomial = polynomial * square - .3332499857920217f;
        polynomial = polynomial * square + 1f;
        float result = polynomial * ratio;
        if (largerY) result = .5f * (float)Math.PI - result;
        if (x < 0) result = (float)Math.PI - result;
        return y < 0 ? -result : result;
    }
}
