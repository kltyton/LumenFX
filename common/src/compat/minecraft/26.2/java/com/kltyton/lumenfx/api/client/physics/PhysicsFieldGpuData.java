package com.kltyton.lumenfx.api.client.physics;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorTarget;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarTarget;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerTarget;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Native Chaos field graph and optional attribute-major clipmap, published together. */
public final class PhysicsFieldGpuData {
    public enum Buffer { CLIPMAP, PARAMETERS, NODES, TARGETS }

    public static final int TARGET_ROWS = 32;
    static final int PHYSICS_TYPES = 20;
    private final int exponent, levels, attributes;
    private final int[] targetAttributes;
    private final int[] physicsAttributes = new int[PHYSICS_TYPES];
    private final boolean generateClipmap;
    private final float[] parameters, clipmap;
    private final int[] nodes, targets;

    public PhysicsFieldGpuData(int exponent, int levels, int attributes, int[] targetAttributes,
                               float[] parameters, int[] nodes, int[] targets, float[] clipmap) {
        this(exponent, levels, attributes, targetAttributes, parameters, nodes, targets, clipmap, false);
    }

    PhysicsFieldGpuData(int exponent, int levels, int attributes, int[] targetAttributes,
                        float[] parameters, int[] nodes, int[] targets, float[] clipmap, boolean generateClipmap) {
        if (exponent < 1 || levels < 1 || levels > 1 && exponent == 1 || attributes < 0
                || targetAttributes.length != TARGET_ROWS * 4 || targets.length != PHYSICS_TYPES + 1
                || targets[0] != 0 || targets[PHYSICS_TYPES] != nodes.length)
            throw new IllegalArgumentException("Invalid Chaos field resource layout");
        this.exponent = exponent;
        this.levels = levels;
        this.attributes = attributes;
        this.targetAttributes = targetAttributes.clone();
        this.parameters = parameters.clone();
        this.nodes = nodes.clone();
        this.targets = targets.clone();
        this.clipmap = clipmap.clone();
        this.generateClipmap = generateClipmap;
        for (int index = 1; index < this.targets.length; index++)
            if (this.targets[index] < this.targets[index - 1])
                throw new IllegalArgumentException("Unordered Chaos physics target offsets");
        for (int offset : this.nodes) if (offset < 0 || offset > this.parameters.length - 2)
            throw new IllegalArgumentException("Chaos physics node offset is outside the parameter buffer");
        for (int index = 0; index < this.targetAttributes.length; index++) {
            int offset = this.targetAttributes[index];
            int width = index % 4 == 0 ? 3 : 1;
            if (index % 4 != 3 && (offset < -1 || offset >= 0 && offset > attributes - width))
                throw new IllegalArgumentException("Chaos clipmap target is outside the attribute channels");
        }
        Arrays.fill(physicsAttributes, -1);
        for (VectorTarget target : VectorTarget.values()) physicsAttributes[target.physicsType()] = this.targetAttributes[target.ordinal() * 4];
        for (ScalarTarget target : ScalarTarget.values()) physicsAttributes[target.physicsType()] = this.targetAttributes[target.ordinal() * 4 + 1];
        for (IntegerTarget target : IntegerTarget.values()) physicsAttributes[target.physicsType()] = this.targetAttributes[target.ordinal() * 4 + 2];
    }

    public void requireResolution(int resolution) {
        if (clipmap.length == 0) return;
        long cells = Math.multiplyExact(Math.multiplyExact((long)resolution, resolution), resolution);
        if (clipmap.length != Math.multiplyExact(Math.multiplyExact(cells, levels), attributes))
            throw new IllegalArgumentException("Chaos clipmap buffer does not match its resolution and attribute layout");
    }

    public int exponent() { return exponent; }
    public int levels() { return levels; }
    public int attributes() { return attributes; }
    public boolean clipmapAvailable() { return generateClipmap || clipmap.length > 0; }
    public boolean generateClipmap() { return generateClipmap; }
    public int targetAttribute(int word) { return targetAttributes[word]; }
    public int[] physicsAttributes() { return physicsAttributes.clone(); }

    public int bytes(Buffer buffer) {
        int words = switch (buffer) {
            case CLIPMAP -> clipmap.length;
            case PARAMETERS -> parameters.length;
            case NODES -> nodes.length;
            case TARGETS -> targets.length;
        };
        return Math.multiplyExact(words, Integer.BYTES);
    }

    public void write(Buffer buffer, ByteBuffer destination) {
        switch (buffer) {
            case CLIPMAP -> { for (float value : clipmap) destination.putFloat(value); }
            case PARAMETERS -> { for (float value : parameters) destination.putFloat(value); }
            case NODES -> { for (int value : nodes) destination.putInt(value); }
            case TARGETS -> { for (int value : targets) destination.putInt(value); }
        }
    }
}
