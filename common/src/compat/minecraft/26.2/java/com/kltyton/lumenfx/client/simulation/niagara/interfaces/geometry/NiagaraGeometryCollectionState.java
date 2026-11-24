package com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry;

import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs.Component;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs.Piece;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs.RestCollection;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.niagara.geometry.GeometryCollectionDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntBinaryOperator;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class NiagaraGeometryCollectionState {
    public enum Function {
        GetNumElements, GetElementBounds, GetElementTransform, SetElementTransform, SetElementTransformWS, GetGeometryComponentTransform
    }
    @FunctionalInterface public interface Output { void set(int word, int lane, int value); }

    private record Selection(@Nullable RestCollection asset, @Nullable Component component) {
        @Nullable RestCollection collection() { return asset != null ? asset : component == null ? null : component.asset(); }
    }

    private static final double HUGE_DISTANCE = 1_048_576;
    private static final Vec3 HUGE_EXTENT = new Vec3(HUGE_DISTANCE, HUGE_DISTANCE, HUGE_DISTANCE);
    private static final int MATRIX_WORDS = 12;
    private static final int REST_WORDS = 10;

    private final GeometryCollectionDefinition definition;
    private final @Nullable RestCollection savedDefault;
    private @Nullable Component component;
    private @Nullable RestCollection lastAsset;
    private List<Piece> pieces = List.of(new Piece(0, true, Vec3.ZERO, Vec3.ZERO));
    private AtomicReferenceArray<Transform> rest = new AtomicReferenceArray<>(new Transform[]{GeometryTransforms.IDENTITY});
    private List<Transform> lastRest = List.of(GeometryTransforms.IDENTITY);
    private Transform root = GeometryTransforms.IDENTITY;
    private Transform rootInverse = GeometryTransforms.IDENTITY;
    private Vec3 boundsOrigin = Vec3.ZERO, boundsExtent = Vec3.ZERO;
    private float[] world = new float[MATRIX_WORDS], inverse = new float[MATRIX_WORDS];
    private float[] previous = world, previousInverse = inverse;
    private float[] bounds = new float[4], restWords = new float[12];
    private volatile boolean pending;
    private boolean restDirty;

    public NiagaraGeometryCollectionState(GeometryCollectionDefinition definition, @Nullable RestCollection savedDefault) {
        this.definition = definition;
        this.savedDefault = savedDefault;
    }
    public String parameter() { return definition.parameter(); }

    public void begin(NiagaraGeometryCollectionInputs inputs, @Nullable String savedObject, @Nullable RestCollection savedParameter, Vec3 origin) {
        var selection = select(inputs, savedObject, savedParameter);
        component = selection.component();
        var asset = selection.collection();
        if (asset == null) return;
        root = GeometryTransforms.translated(component == null ? GeometryTransforms.IDENTITY : component.root(), origin.scale(-1));
        rootInverse = GeometryTransforms.inverse(root);
        boundsOrigin = (component == null ? Vec3.ZERO : component.boundsOrigin()).subtract(origin);
        boundsExtent = component == null ? HUGE_EXTENT : component.boundsExtent();
        boolean topologyChanged = lastAsset != asset;
        if (topologyChanged) {
            pieces = asset.pieces().stream().filter(piece -> definition.includeIntermediate() || piece.leaf()).toList();
            bounds = new float[Math.multiplyExact(pieces.size(), 4)];
            for (int i = 0; i < pieces.size(); i++) {
                var piece = pieces.get(i);
                var size = piece.maximum().subtract(piece.minimum());
                bounds[i * 4] = (float)size.x;
                bounds[i * 4 + 1] = (float)size.y;
                bounds[i * 4 + 2] = (float)size.z;
            }
            restDirty = true;
            lastAsset = asset;
        }
        var actualRest = component == null ? asset.globalRest() : component.localRest();
        if (topologyChanged || !lastRest.equals(actualRest) || !matchesCurrent(actualRest)) {
            lastRest = actualRest;
            rest = new AtomicReferenceArray<>(actualRest.toArray(Transform[]::new));
            restDirty = true;
        }
        int words = Math.multiplyExact(pieces.size(), MATRIX_WORDS);
        previous = world.length == words ? world : new float[words];
        previousInverse = inverse.length == words ? inverse : new float[words];
        world = new float[words];
        inverse = new float[words];
        for (int i = 0; i < pieces.size(); i++) {
            var piece = pieces.get(i);
            Vec3 center = piece.minimum().add(piece.maximum()).scale(.5);
            Transform element = component != null && piece.transform() < component.componentSpace().size()
                    ? component.componentSpace().get(piece.transform()) : GeometryTransforms.IDENTITY;
            var local = GeometryTransforms.compose(GeometryTransforms.translated(GeometryTransforms.IDENTITY, center), element);
            GeometryTransforms.packedMatrices(GeometryTransforms.compose(local, root), world, inverse, i * MATRIX_WORDS);
        }
    }

    private boolean matchesCurrent(List<Transform> actual) {
        if (actual.size() != rest.length()) return false;
        for (int i = 0; i < actual.size(); i++) if (!actual.get(i).equals(rest.get(i))) return false;
        return true;
    }

    private Selection select(NiagaraGeometryCollectionInputs inputs, @Nullable String savedObject, @Nullable RestCollection savedParameter) {
        var direct = inputs.components().get(definition.sourceComponent());
        var actor = inputs.components().get(definition.sourceActor());
        var fromParameter = inputs.parameters().get(definition.parameter());
        if (fromParameter == null && savedObject != null) {
            fromParameter = inputs.components().get(savedObject);
            if (fromParameter == null) fromParameter = inputs.assets().get(savedObject);
        }
        var defaults = inputs.assets().get(definition.defaultCollection());
        if (fromParameter == null) fromParameter = savedParameter;
        if (defaults == null) defaults = savedDefault;
        return switch (definition.mode()) {
            case Source -> new Selection(null, direct != null ? direct : actor);
            case AttachParent -> new Selection(null, inputs.attachment());
            case DefaultCollectionOnly -> new Selection(defaults, null);
            case ParameterBinding -> fromParameter instanceof Component bound ? new Selection(null, bound)
                    : new Selection((RestCollection)fromParameter, null);
            case Default -> {
                if (direct != null) yield new Selection(null, direct);
                if (fromParameter instanceof Component bound) yield new Selection(null, bound);
                if (fromParameter instanceof RestCollection restAsset) yield new Selection(restAsset, actor);
                if (inputs.attachment() != null) yield new Selection(null, inputs.attachment());
                yield new Selection(defaults, actor);
            }
        };
    }

    public void execute(Function function, int count, IntBinaryOperator input, Output output) {
        for (int lane = 0; lane < count; lane++) {
            switch (function) {
                case GetNumElements -> output.set(0, lane, pieces.size());
                case GetGeometryComponentTransform -> transform(output, lane, root);
                case GetElementBounds, GetElementTransform, SetElementTransform, SetElementTransformWS -> {
                    int element = input.applyAsInt(0, lane);
                    int index = transformIndex(element);
                    var value = index < 0 ? GeometryTransforms.IDENTITY : rest.get(index);
                    switch (function) {
                        case GetElementBounds -> {
                            vector(output, lane, 0, value.x(), value.y(), value.z());
                            if (index < 0) vector(output, lane, 3, 0, 0, 0);
                            else vector(output, lane, 3, bounds[4 * element], bounds[4 * element + 1], bounds[4 * element + 2]);
                        }
                        case GetElementTransform -> transform(output, lane, value);
                        case SetElementTransform, SetElementTransformWS -> {
                            if (index < 0) continue;
                            var next = read(input, lane);
                            if (function == Function.SetElementTransformWS) next = GeometryTransforms.compose(next, rootInverse);
                            if (!GeometryTransforms.equivalent(value, next)) {
                                rest.set(index, next);
                                pending = true;
                            }
                        }
                        default -> throw new IllegalStateException("Unexpected geometry element function " + function);
                    }
                }
            }
        }
    }

    private int transformIndex(int element) {
        if (element < 0 || element >= pieces.size()) return -1;
        int index = pieces.get(element).transform();
        return index >= rest.length() ? -1 : index;
    }

    public void postSimulate() {
        var target = component;
        if (!pending || target == null) return;
        var snapshot = new ArrayList<Transform>(rest.length());
        for (int index = 0; index < rest.length(); index++) snapshot.add(rest.get(index));
        target.restTransforms().apply(List.copyOf(snapshot), !definition.includeIntermediate());
    }

    public GeometryCollectionFrame capture() {
        if (restDirty) {
            restDirty = false;
            int length = Math.multiplyExact(pieces.size(), REST_WORDS);
            restWords = new float[Math.max(4, Math.addExact(length, 3) & ~3)];
            for (int i = 0; i < pieces.size(); i++) {
                int index = transformIndex(i);
                if (index >= 0) pack(rest.get(index), restWords, i * REST_WORDS);
            }
        }
        return new GeometryCollectionFrame(root, boundsOrigin, boundsExtent, pieces.size(), world, previous, inverse,
                previousInverse, bounds, restWords);
    }

    private static Transform read(IntBinaryOperator input, int lane) {
        return new Transform(number(input, lane, 1), number(input, lane, 2), number(input, lane, 3),
                number(input, lane, 4), number(input, lane, 5), number(input, lane, 6), number(input, lane, 7),
                number(input, lane, 8), number(input, lane, 9), number(input, lane, 10));
    }

    private static float number(IntBinaryOperator input, int lane, int word) {
        return Float.intBitsToFloat(input.applyAsInt(word, lane));
    }

    private static void transform(Output output, int lane, Transform value) {
        vector(output, lane, 0, value.x(), value.y(), value.z());
        var q = GeometryTransforms.rotation(value);
        if (q.lengthSquared() >= 1e-8) q.normalize();
        else q.identity();
        vector(output, lane, 3, q.x, q.y, q.z);
        output.set(6, lane, Float.floatToRawIntBits((float)q.w));
        vector(output, lane, 7, value.scaleX(), value.scaleY(), value.scaleZ());
    }

    private static void vector(Output output, int lane, int word, double x, double y, double z) {
        output.set(word, lane, Float.floatToRawIntBits((float)x));
        output.set(word + 1, lane, Float.floatToRawIntBits((float)y));
        output.set(word + 2, lane, Float.floatToRawIntBits((float)z));
    }

    private static void pack(Transform value, float[] words, int offset) {
        words[offset] = (float)value.x(); words[offset + 1] = (float)value.y(); words[offset + 2] = (float)value.z();
        words[offset + 3] = (float)value.qx(); words[offset + 4] = (float)value.qy();
        words[offset + 5] = (float)value.qz(); words[offset + 6] = (float)value.qw();
        words[offset + 7] = (float)value.scaleX(); words[offset + 8] = (float)value.scaleY(); words[offset + 9] = (float)value.scaleZ();
    }
}
