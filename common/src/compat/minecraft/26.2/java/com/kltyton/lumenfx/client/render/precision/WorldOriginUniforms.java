package com.kltyton.lumenfx.client.render.precision;

import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;

/** Tile-offset view data in source centimeters and source axis order. */
public final class WorldOriginUniforms {
    public static final int BYTES = 32;
    private static final double TILE_SIZE = 2097152;

    private WorldOriginUniforms() { }

    public static void write(ByteBuffer destination, Vec3 position) {
        write(destination, position, position);
    }

    public static void write(ByteBuffer destination, Vec3 currentCamera, Vec3 sampleCamera) {
        double tileX = Math.floor(currentCamera.x * 100 / TILE_SIZE + .5);
        double tileY = Math.floor(currentCamera.z * 100 / TILE_SIZE + .5);
        double tileZ = Math.floor(currentCamera.y * 100 / TILE_SIZE + .5);
        destination.putFloat((float)tileX).putFloat((float)tileY).putFloat((float)tileZ).putFloat(0);
        destination.putFloat((float)(-sampleCamera.x * 100 + tileX * TILE_SIZE))
                .putFloat((float)(-sampleCamera.z * 100 + tileY * TILE_SIZE))
                .putFloat((float)(-sampleCamera.y * 100 + tileZ * TILE_SIZE)).putFloat(0);
    }

    public static void writeSourcePosition(ByteBuffer destination, double x, double y, double z) {
        double tileX = Math.floor(x / TILE_SIZE + .5);
        double tileY = Math.floor(y / TILE_SIZE + .5);
        double tileZ = Math.floor(z / TILE_SIZE + .5);
        destination.putFloat((float)tileX).putFloat((float)tileY).putFloat((float)tileZ).putFloat(0);
        destination.putFloat((float)(x - tileX * TILE_SIZE)).putFloat((float)(y - tileY * TILE_SIZE))
                .putFloat((float)(z - tileZ * TILE_SIZE)).putFloat(0);
    }

    public static void writeViewOrigin(ByteBuffer destination, Vec3 camera, Matrix4fc modelView) {
        var inverseView = new Matrix4d(modelView).invert();
        double tileX = Math.floor(camera.x * 100 / TILE_SIZE + .5);
        double tileY = Math.floor(camera.z * 100 / TILE_SIZE + .5);
        double tileZ = Math.floor(camera.y * 100 / TILE_SIZE + .5);
        destination.putFloat((float)((inverseView.m30() * 100 + camera.x * 100) - tileX * TILE_SIZE))
                .putFloat((float)((inverseView.m32() * 100 + camera.z * 100) - tileY * TILE_SIZE))
                .putFloat((float)((inverseView.m31() * 100 + camera.y * 100) - tileZ * TILE_SIZE)).putFloat(0);
    }

    public static void writeDoublePreViewTranslation(ByteBuffer destination, Vec3 camera) {
        double x = -camera.x * 100, y = -camera.z * 100, z = -camera.y * 100;
        float highX = (float)x, highY = (float)y, highZ = (float)z;
        destination.putFloat(highX).putFloat(highY).putFloat(highZ).putFloat(0);
        destination.putFloat((float)(x - highX)).putFloat((float)(y - highY)).putFloat((float)(z - highZ)).putFloat(0);
    }
}
