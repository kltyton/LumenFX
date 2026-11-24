package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ColorFunction.Color;
import com.kltyton.lumenfx.effect.EmitterDefinition.Shape;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public record UnityShapeTexture(Pixels pixels, int channel, float threshold, boolean affectsColor, boolean affectsAlpha,
                                boolean bilinear, Wrap wrapU, Wrap wrapV) {
    public record Projection(UnityShapeTexture image, boolean forwardToUp) {
        public @Nullable Color sample(Shape shape, Vec3 position, double azimuth, double coneOuterRadius) {
            var size = shape.size();
            double x = normalized(position.x, size.x), y = normalized(position.y, size.y), z = normalized(position.z, size.z);
            return switch (shape.type()) {
                case SPHERE, HEMISPHERE -> {
                    double latitude = Math.atan2(-z, Math.hypot(x, y));
                    double longitude = Math.atan2(y, x);
                    yield image.sample(.5 + longitude / (2 * Math.PI), .5 + latitude / Math.PI);
                }
                case CIRCLE -> image.sample(.5 + x * .5, .5 - z * .5);
                case CONE -> image.sample(.5 + normalized(x, coneOuterRadius) * .5,
                        .5 - normalized(z, coneOuterRadius) * .5);
                case DONUT -> {
                    var donut = Objects.requireNonNull(Objects.requireNonNull(shape.unity()).donut());
                    double radial = (x * Math.cos(azimuth) + z * Math.sin(azimuth)) - donut.ringRadius();
                    yield image.sample(.5 + normalized(radial, donut.tubeRadius()) * .5,
                            .5 + normalized(y, donut.tubeRadius()) * .5);
                }
                case BOX, BOX_EDGE -> image.sample(.5 + x * .5, .5 + (forwardToUp ? -z : y) * .5);
                case POINT -> image.sample(.5, .5);
                default -> throw new IllegalArgumentException("Unity shape texture requires an authored projection");
            };
        }

        private static double normalized(double value, double extent) {
            return extent == 0 ? 0 : value / extent;
        }
    }
    public enum Wrap {
        Repeat, Clamp, Mirror, MirrorOnce;

        private double normalized(double coordinate) {
            return switch (this) {
                case Repeat -> coordinate - Math.floor(coordinate);
                case Clamp -> Math.clamp(coordinate, 0, 1);
                case Mirror -> {
                    double phase = coordinate - 2 * Math.floor(coordinate / 2);
                    yield Math.min(phase, 2 - phase);
                }
                case MirrorOnce -> Math.min(Math.abs(coordinate), 1);
            };
        }

        private int index(int coordinate, int size) {
            return switch (this) {
                case Repeat -> Math.floorMod(coordinate, size);
                case Clamp -> Math.clamp(coordinate, 0, size - 1);
                case Mirror -> {
                    int position = Math.floorMod(coordinate, 2 * size);
                    yield position < size ? position : 2 * size - 1 - position;
                }
                case MirrorOnce -> Math.min(coordinate < 0 ? -1 - coordinate : coordinate, size - 1);
            };
        }
    }

    public static final class Pixels {
        private final int width, height;
        private final float[] rgba;

        public Pixels(int width, int height, float[] rgba) {
            if (width < 1 || height < 1 || Math.multiplyExact(Math.multiplyExact(width, height), 4) != rgba.length)
                throw new IllegalArgumentException("Invalid Unity shape image dimensions");
            this.width = width;
            this.height = height;
            this.rgba = rgba.clone();
            for (float value : this.rgba) if (!Float.isFinite(value))
                throw new IllegalArgumentException("Non-finite Unity shape image pixel");
        }
    }

    public UnityShapeTexture {
        if (channel < 0 || channel > 3 || !Float.isFinite(threshold) || threshold < 0 || threshold > 1)
            throw new IllegalArgumentException("Invalid Unity shape texture clip");
    }

    /** A clipped sample returns null. Coordinates are in Unity's bottom-up UV space. */
    public @Nullable Color sample(double u, double v) {
        double x = wrapU.normalized(u) * pixels.width, y = wrapV.normalized(v) * pixels.height;
        if (bilinear) { x -= .5; y -= .5; }
        int ix = (int)Math.floor(x), iy = (int)Math.floor(y);
        int lowerLeft = pixel(ix, iy);
        int lowerRight = bilinear ? pixel(ix + 1, iy) : lowerLeft;
        int upperLeft = bilinear ? pixel(ix, iy + 1) : lowerLeft;
        int upperRight = bilinear ? pixel(ix + 1, iy + 1) : lowerLeft;
        double fx = x - ix, fy = y - iy;
        float red = interpolate(0, lowerLeft, lowerRight, upperLeft, upperRight, fx, fy);
        float green = interpolate(1, lowerLeft, lowerRight, upperLeft, upperRight, fx, fy);
        float blue = interpolate(2, lowerLeft, lowerRight, upperLeft, upperRight, fx, fy);
        float alpha = interpolate(3, lowerLeft, lowerRight, upperLeft, upperRight, fx, fy);
        float clip = switch (channel) { case 0 -> red; case 1 -> green; case 2 -> blue; default -> alpha; };
        if (clip < threshold) return null;
        return new Color(affectsColor ? red : 1, affectsColor ? green : 1, affectsColor ? blue : 1, affectsAlpha ? alpha : 1);
    }

    private int pixel(int x, int y) {
        return 4 * (wrapU.index(x, pixels.width) + wrapV.index(y, pixels.height) * pixels.width);
    }

    private float interpolate(int channel, int a, int b, int c, int d, double x, double y) {
        var values = pixels.rgba;
        double left = values[a + channel], right = values[c + channel];
        double first = left + (values[b + channel] - left) * x, second = right + (values[d + channel] - right) * x;
        return (float)(first + (second - first) * y);
    }
}
