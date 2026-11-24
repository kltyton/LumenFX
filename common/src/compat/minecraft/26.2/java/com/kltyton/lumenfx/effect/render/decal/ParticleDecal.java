package com.kltyton.lumenfx.effect.render.decal;

import net.minecraft.world.phys.Vec3;
import com.kltyton.lumenfx.effect.MaterialDefinition;

/** Positions and rotations use Minecraft axes; half dimensions retain source XYZ centimetres. */
public record ParticleDecal(MaterialDefinition material, long sourcePrimitive, String renderer, int proxySlot, Vec3 position,
                            double rotationX, double rotationY, double rotationZ, double rotationW, Vec3 halfSizeCentimeters,
                            float red, float green, float blue, float alpha, float fade, int sortOrder,
                            float screenSizeFade, @org.jspecify.annotations.Nullable DecalFading fading,
                            double updateWorldSeconds) {
    public ParticleDecal(MaterialDefinition material, long sourcePrimitive, String renderer, int proxySlot, Vec3 position,
                         double rotationX, double rotationY, double rotationZ, double rotationW, Vec3 halfSizeCentimeters,
                         float red, float green, float blue, float alpha, float fade, int sortOrder, float screenSizeFade) {
        this(material, sourcePrimitive, renderer, proxySlot, position, rotationX, rotationY, rotationZ, rotationW,
                halfSizeCentimeters, red, green, blue, alpha, fade, sortOrder, screenSizeFade, null, Double.NaN);
    }

    public ParticleDecal(MaterialDefinition material, long sourcePrimitive, String renderer, int proxySlot, Vec3 position,
                         double rotationX, double rotationY, double rotationZ, double rotationW, Vec3 halfSizeCentimeters,
                         float red, float green, float blue, float alpha, float fade, int sortOrder, float screenSizeFade,
                         @org.jspecify.annotations.Nullable DecalFading fading) {
        this(material, sourcePrimitive, renderer, proxySlot, position, rotationX, rotationY, rotationZ, rotationW,
                halfSizeCentimeters, red, green, blue, alpha, fade, sortOrder, screenSizeFade, fading, Double.NaN);
    }
}
