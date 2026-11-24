package com.kltyton.lumenfx.client.render;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import com.kltyton.lumenfx.effect.render.SpriteCutout;
import com.kltyton.lumenfx.effect.render.mesh.MeshBatchGeometry;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import org.jspecify.annotations.Nullable;
import java.util.Arrays;
import net.minecraft.world.phys.Vec3;

/** Frame-owned instance stream, refilled after its owning batch has been uploaded. */
public final class EffectMesh {
    public static final int RIBBON_EXPLICIT_CORNERS = 1 << 16;
    public static final int RIBBON_ALTERNATE_DIAGONAL = 1 << 17;
    public enum Kind {
        SPRITE(48), RIBBON(59), MESH(35);
        private final int stride;
        Kind(int stride) { this.stride = stride; }
        public int floatsPerInstance() { return stride; }
    }

    public record Key(MaterialDefinition material, Kind kind, int widthSegments, int sortOrder, long primitive,
                      SpriteCoverage coverage, boolean disableCull, @Nullable SpriteCutout cutout,
                      @Nullable MeshBatchGeometry mesh, boolean particleMotion, boolean componentVelocity,
                      boolean receivesDecals, int drawRun) {
        public Key(MaterialDefinition material, Kind kind, int widthSegments, int sortOrder, long primitive,
                   SpriteCoverage coverage, boolean disableCull, @Nullable SpriteCutout cutout,
                   @Nullable MeshBatchGeometry mesh, boolean particleMotion, boolean componentVelocity, boolean receivesDecals) {
            this(material, kind, widthSegments, sortOrder, primitive, coverage, disableCull, cutout, mesh,
                    particleMotion, componentVelocity, receivesDecals, 0);
        }
        public Key(MaterialDefinition material, Kind kind, int widthSegments, int sortOrder, long primitive,
                   SpriteCoverage coverage, boolean disableCull, @Nullable SpriteCutout cutout,
                   @Nullable MeshBatchGeometry mesh, boolean particleMotion, boolean componentVelocity) {
            this(material, kind, widthSegments, sortOrder, primitive, coverage, disableCull, cutout, mesh, particleMotion, componentVelocity, true);
        }
        public Key(MaterialDefinition material, Kind kind, int widthSegments, int sortOrder, long primitive,
                   SpriteCoverage coverage, boolean disableCull, @Nullable SpriteCutout cutout,
                   @Nullable MeshBatchGeometry mesh, boolean particleMotion) {
            this(material, kind, widthSegments, sortOrder, primitive, coverage, disableCull, cutout, mesh, particleMotion, true);
        }
        public Key(MaterialDefinition material, Kind kind, int widthSegments, int sortOrder, long primitive,
                   SpriteCoverage coverage, boolean disableCull, @Nullable SpriteCutout cutout, @Nullable MeshBatchGeometry mesh) {
            this(material, kind, widthSegments, sortOrder, primitive, coverage, disableCull, cutout, mesh, false);
        }
        public Key(MaterialDefinition material, Kind kind, int widthSegments, int sortOrder, long primitive,
                   SpriteCoverage coverage, boolean disableCull, @Nullable SpriteCutout cutout) {
            this(material, kind, widthSegments, sortOrder, primitive, coverage, disableCull, cutout, null);
        }
        public Key(MaterialDefinition material, Kind kind, int widthSegments, int sortOrder, long primitive,
                   SpriteCoverage coverage, boolean disableCull) {
            this(material, kind, widthSegments, sortOrder, primitive, coverage, disableCull, null);
        }
        public Key(MaterialDefinition material, Kind kind, int widthSegments, int sortOrder, long primitive, SpriteCoverage coverage) {
            this(material, kind, widthSegments, sortOrder, primitive, coverage, false);
        }
        public Key(MaterialDefinition material, Kind kind, int widthSegments, int sortOrder, long primitive) {
            this(material, kind, widthSegments, sortOrder, primitive, SpriteCoverage.DISABLED, false);
        }
    }
    private static final float[] EMPTY = new float[0];

    private final Key key;
    private float[] values = EMPTY;
    private float[] extraValues = EMPTY;
    private int size;
    private double depth;
    private Vec3 actorPosition = Vec3.ZERO;
    private double macroUVRadius;
    private boolean visited;
    private boolean firstPerson;
    private boolean niagaraRenderer;
    private @Nullable UnityRendererBinding unityRenderer;
    private long unityInstanceId;
    private boolean directionalRibbonBasis;
    private com.kltyton.lumenfx.client.render.mesh.scene.@Nullable MeshEmitterSceneData emitterScene;
    private com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame materialPrimitive;

    EffectMesh(Key key) {
        this.key = key;
    }

    public Key key() { return key; }
    public MaterialDefinition material() { return key.material(); }
    public Kind kind() { return key.kind(); }
    public int widthSegments() { return key.widthSegments(); }
    public int instanceCount() { return size / key.kind().floatsPerInstance(); }
    public int verticesPerInstance() { return key.kind() == Kind.SPRITE
            ? key.cutout() == null ? 6 : key.cutout().indexCount()
            : key.kind() == Kind.MESH ? key.mesh().geometry().sections().get(key.mesh().section()).indexCount() : key.widthSegments() * 6; }
    public int byteSize() { return Math.multiplyExact(size, Float.BYTES); }
    public double depth() { return depth; }
    public Vec3 actorPosition() { return actorPosition; }
    public double macroUVRadius() { return macroUVRadius; }
    public boolean firstPerson() { return firstPerson; }
    public void firstPerson(boolean value) { firstPerson = value; }
    boolean niagaraRenderer() { return niagaraRenderer; }
    void niagaraRenderer(boolean value) { niagaraRenderer = value; }
    @Nullable UnityRendererBinding unityRenderer() { return unityRenderer; }
    void unityRenderer(@Nullable UnityRendererBinding value) { unityRenderer = value; }
    long unityInstanceId() { return unityInstanceId; }
    void unityInstanceId(long value) { unityInstanceId = value; }
    public com.kltyton.lumenfx.client.render.mesh.scene.@Nullable MeshEmitterSceneData emitterScene() { return emitterScene; }
    public void emitterScene(com.kltyton.lumenfx.client.render.mesh.scene.@Nullable MeshEmitterSceneData value) { emitterScene = value; }
    public com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame materialPrimitive() { return materialPrimitive; }
    public void materialPrimitive(com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame frame) { materialPrimitive = frame; }
    void macroUV(Vec3 position, double radius) { actorPosition = position; macroUVRadius = radius; }
    void depth(double value) { depth = value; }
    int floatCount() { return size; }
    float[] values() { return values; }
    float[] extraValues() { return extraValues; }
    public int materialBasisOffset() { return key.kind() == Kind.MESH ? 40 : key.particleMotion() ? key.kind() == Kind.SPRITE ? 32 : 56
            : key.kind() != Kind.RIBBON ? 12 : key.material().ribbonParticleData() ? 32 : 24; }
    boolean directionalRibbonBasis() { return directionalRibbonBasis; }
    void directionalRibbonBasis(boolean value) { directionalRibbonBasis = value; }
    public boolean needsMaterialBasis() { return key.kind() == Kind.RIBBON && (directionalRibbonBasis || key.material().usesVertexBasis()); }
    public int extraFloatsPerInstance() { return materialBasisOffset() + (needsMaterialBasis() ? 28 : 0); }
    boolean needsExtra() { return key.kind() == Kind.MESH || key.particleMotion() || (key.material().dynamicSlots() & 14) != 0 || key.kind() == Kind.RIBBON && (key.material().ribbonParticleData() || needsMaterialBasis()); }
    int extraFloatCount() { return needsExtra() ? instanceCount() * extraFloatsPerInstance() : 0; }

    void reset() { size = 0; visited = false; materialPrimitive = null; firstPerson = false; niagaraRenderer = false; unityRenderer = null; unityInstanceId = 0; directionalRibbonBasis = false; emitterScene = null; }
    void visit() { visited = true; }
    boolean visited() { return visited; }

    int appendInstance() {
        int stride = key.kind().floatsPerInstance();
        int initialInstances = key.drawRun() == 0 ? 256 : 1;
        int next = Math.addExact(size, stride);
        if (values.length < next) {
            int capacity = values.length == 0 ? Math.multiplyExact(stride, initialInstances) : Math.multiplyExact(values.length, 2);
            values = Arrays.copyOf(values, Math.max(next, capacity));
        }
        int start = size;
        size = next;
        if (needsExtra()) {
            int required = Math.multiplyExact(instanceCount(), extraFloatsPerInstance());
            if (extraValues.length < required)
                extraValues = Arrays.copyOf(extraValues, Math.max(required,
                        Math.max(extraFloatsPerInstance() * initialInstances, extraValues.length * 2)));
        }
        return start;
    }

    public void appendInstance(float[] instance, int instanceOffset, float[] extra, int extraOffset) {
        int count = key.kind().floatsPerInstance();
        int extraCount = needsExtra() ? extraFloatsPerInstance() : 0;
        java.util.Objects.checkFromIndexSize(instanceOffset, count, instance.length);
        java.util.Objects.checkFromIndexSize(extraOffset, extraCount, extra.length);
        int start = appendInstance();
        System.arraycopy(instance, instanceOffset, values, start, count);
        if (extraCount != 0) System.arraycopy(extra, extraOffset, extraValues, (instanceCount() - 1) * extraCount, extraCount);
    }

    public void appendInstance(float[] instance, float[] extra) {
        boolean legacyRibbon = key.kind() == Kind.RIBBON && instance.length == 53;
        boolean uniformMesh = key.kind() == Kind.MESH && extra.length == 36;
        if (!legacyRibbon && instance.length != key.kind().floatsPerInstance()
                || needsExtra() && extra.length != extraFloatsPerInstance() && !uniformMesh)
            throw new IllegalArgumentException("Instance stream layout disagrees with its material/geometry");
        int start = appendInstance();
        System.arraycopy(instance, 0, values, start, instance.length);
        if (legacyRibbon) System.arraycopy(instance, 0, values, start + 53, 6);
        if (needsExtra()) {
            int destination = (instanceCount() - 1) * extraFloatsPerInstance();
            System.arraycopy(extra, 0, extraValues, destination, extra.length);
            if (uniformMesh) {
                extraValues[destination + 36] = extraValues[destination + 37] = instance[30];
                extraValues[destination + 38] = extraValues[destination + 39] = extra[31];
            }
        }
    }
}
