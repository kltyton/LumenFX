package com.kltyton.lumenfx.client.render.ribbon;

import static com.kltyton.lumenfx.client.render.math.UnrealRendererMath.acosFast;

import com.kltyton.lumenfx.client.render.niagara.sort.NiagaraIndexSort;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParticles;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraRibbonScalability;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Source CPU ribbon ordering, segment filtering, tangents and UV lookup data in float centimetres. */
public final class NiagaraCpuRibbonData {
    public record Uv(float scale, float offset, float distribution, boolean override) { }
    public record Strip(int first, int last, float length, Uv uv0, Uv uv1, int sourceFirstRow, int sourceLastRow) { }
    public record Metrics(float length, float weightedLength, float weightedAngle, float weightedTwist, float weightedWidth) {
        private static final Metrics EMPTY = new Metrics(0, 0, 0, 0, 0);
    }

    private final NiagaraIndexSort sorter = new NiagaraIndexSort();
    private final Long2IntOpenHashMap groupIndices = new Long2IntOpenHashMap();
    private final List<Strip> strips = new ArrayList<>();
    private int[] input = new int[0], rows = new int[0], groupHeads = new int[0], groupTails = new int[0], next = new int[0], groupOrder = new int[0];
    private long[] groupIds = new long[0];
    private float[] tangents = new float[0];
    private NiagaraParticles particles;
    private int points;
    private float total, weightedLength, weightedAngle, weightedTwist, weightedWidth;
    private Metrics metrics = Metrics.EMPTY;
    private final IntComparator idOrder = (first, second) -> particles.fullRibbonIds()
            ? Long.compare(groupIds[first], groupIds[second]) : Long.compareUnsigned(groupIds[first], groupIds[second]);
    private final IntComparator linkOrder = (first, second) -> {
        double a = particles.ribbonLink(first), b = particles.ribbonLink(second);
        if (particles.ribbonLinkIsFloat()) return a < b ? -1 : a > b ? 1 : 0;
        return a > b ? -1 : a < b ? 1 : 0;
    };

    public NiagaraCpuRibbonData() { groupIndices.defaultReturnValue(-1); }

    /** False matches the source's absent dynamic data; valid but empty geometry still updates curvature history. */
    public boolean generate(NiagaraParticles source, RibbonSettings settings, NiagaraRibbonScalability scalability) {
        clear();
        particles = source;
        int count = source.count();
        if (!source.canRenderRibbon() || count < 2) return false;
        reserve(count);
        int groups = 0;
        for (int row = 0; row < count; row++) {
            long id = source.ribbonId(row);
            int group = groupIndices.get(id);
            if (group == -1) {
                group = groups++;
                groupIndices.put(id, group); groupIds[group] = id;
                groupHeads[group] = groupTails[group] = row;
            } else { next[groupTails[group]] = row; groupTails[group] = row; }
            next[row] = -1;
        }
        for (int group = 0; group < groups; group++) groupOrder[group] = group;
        sorter.sort(groupOrder, 0, groups, idOrder);
        boolean automatic = settings.tessellationMode() != RibbonSettings.TessellationMode.DISABLED && !scalability.constant(settings);
        boolean twist = automatic && source.hasRibbonTwistAndWidth();
        for (int group = 0; group < groups; group++) {
            int length = 0;
            for (int row = groupHeads[groupOrder[group]]; row != -1; row = next[row]) input[length++] = row;
            sorter.sort(input, 0, length, linkOrder);
            if (length > 1) generateStrip(length, settings, scalability.minimumSegmentCm(), automatic, twist);
        }
        metrics = new Metrics(total, weightedLength, weightedAngle, weightedTwist, weightedWidth);
        return true;
    }

    public void clear() {
        particles = null;
        groupIndices.clear(); strips.clear(); points = 0;
        total = weightedLength = weightedAngle = weightedTwist = weightedWidth = 0;
        metrics = Metrics.EMPTY;
    }

    public int pointCount() { return points; }
    public int row(int point) { return rows[point]; }
    public float tangent(int point, int axis) { return tangents[point * 4 + axis]; }
    public float distance(int point) { return tangents[point * 4 + 3]; }
    public List<Strip> strips() { return strips; }
    public Metrics metrics() { return metrics; }

    private void reserve(int count) {
        if (rows.length >= count) return;
        int capacity = Math.max(count, Math.max(256, Math.multiplyExact(rows.length, 2)));
        input = Arrays.copyOf(input, capacity); rows = Arrays.copyOf(rows, capacity);
        groupHeads = Arrays.copyOf(groupHeads, capacity); groupTails = Arrays.copyOf(groupTails, capacity);
        next = Arrays.copyOf(next, capacity); groupOrder = Arrays.copyOf(groupOrder, capacity);
        groupIds = Arrays.copyOf(groupIds, capacity); tangents = Arrays.copyOf(tangents, Math.multiplyExact(capacity, 4));
    }

    private void generateStrip(int count, RibbonSettings settings, float minimum, boolean automatic, boolean twist) {
        int start = points, current = 1;
        float tension = Math.min((float)settings.tension(), .99f);
        float firstX = position(input[0], 0), firstY = position(input[0], 1), firstZ = position(input[0], 2);
        float x = firstX, y = firstY, z = firstZ, dx = 0, dy = 0, dz = 0, previousLength = 0, previousTwist = 0, previousWidth = 0, distance = 0;
        while (current < count) {
            int row = input[current];
            x = position(row, 0); y = position(row, 1); z = position(row, 2);
            dx = x - firstX; dy = y - firstY; dz = z - firstZ;
            previousLength = length(dx, dy, dz);
            if (twist) { previousTwist = particles.ribbonTwist(row); previousWidth = particles.ribbonWidthSource(row); }
            if (previousLength > minimum) {
                float inverse = 1f / previousLength;
                dx *= inverse; dy *= inverse; dz *= inverse;
                add(input[0], dx, dy, dz, 0);
                break;
            }
            previousLength = 0;
            current++;
        }
        for (int nextIndex = current + 1; nextIndex < count; nextIndex++) {
            int row = input[nextIndex];
            float nx = position(row, 0), ny = position(row, 1), nz = position(row, 2);
            float sx = nx - x, sy = ny - y, sz = nz - z;
            float segment = length(sx, sy, sz);
            float nextTwist = twist ? particles.ribbonTwist(row) : 0;
            float nextWidth = twist ? particles.ribbonWidthSource(row) : 0;
            if (segment > minimum || nextIndex == count - 1) {
                float denominator = Math.max(minimum, segment);
                float inverse = denominator > 0 ? 1f / denominator : 0f;
                sx *= inverse; sy *= inverse; sz *= inverse;
                float tx = dx + sx, ty = dy + sy, tz = dz + sz;
                float square = (tx * tx + ty * ty) + tz * tz;
                inverse = square < 1.e-8f ? 0 : 1f / (float)Math.sqrt(square);
                tx = (1f - tension) * (tx * inverse); ty = (1f - tension) * (ty * inverse); tz = (1f - tension) * (tz * inverse);
                distance += previousLength;
                add(input[current], tx, ty, tz, distance);
                total += segment;
                if (automatic) {
                    weightedLength += segment * segment;
                    weightedAngle += segment * acosFast((dx * sx + dy * sy) + dz * sz);
                    if (twist) {
                        weightedTwist += segment * Math.abs(nextTwist - previousTwist);
                        weightedWidth += segment * previousWidth;
                    }
                }
                current = nextIndex; x = nx; y = ny; z = nz;
                dx = sx; dy = sy; dz = sz; previousLength = segment;
                previousTwist = nextTwist; previousWidth = nextWidth;
            }
        }
        if (previousLength > 0) {
            distance += previousLength;
            add(input[current], dx, dy, dz, distance);
        }
        int end = points - 1;
        if (end <= start) return;
        if (end - start > 1) { reflect(start, start + 1); reflect(end, end - 1); }
        strips.add(new Strip(start, end, distance,
                uv(settings.uv0(), start, end, distance, particles.hasRibbonU(0)),
                uv(settings.uv1(), start, end, distance, particles.hasRibbonU(1)), input[0], input[count - 1]));
    }

    private void add(int row, float x, float y, float z, float distance) {
        int offset = points * 4;
        rows[points++] = row;
        tangents[offset] = x; tangents[offset + 1] = y; tangents[offset + 2] = z; tangents[offset + 3] = distance;
    }

    private void reflect(int point, int neighbor) {
        int p = point * 4, n = neighbor * 4;
        float dot = (tangents[p] * tangents[n] + tangents[p + 1] * tangents[n + 1]) + tangents[p + 2] * tangents[n + 2];
        float factor = 2f * dot;
        for (int axis = 0; axis < 3; axis++) tangents[p + axis] = factor * tangents[p + axis] - tangents[n + axis];
    }

    private Uv uv(RibbonSettings.Uv settings, int first, int last, float length, boolean hasOverride) {
        if (settings.uOverride() && hasOverride) return new Uv(1, 0, 1, true);
        float firstAge = particles.ribbonNormalizedAge(rows[first]), secondAge = particles.ribbonNormalizedAge(rows[first + 1]);
        float previousAge = particles.ribbonNormalizedAge(rows[last - 1]), lastAge = particles.ribbonNormalizedAge(rows[last]);
        float startStep = secondAge - firstAge, endStep = lastAge - previousAge;
        float startOffset = firstAge < startStep ? startStep - firstAge : 0;
        float endOffset = 1f - lastAge < endStep ? endStep - (1f - lastAge) : 0;
        float leading = settings.leadingEdge() == RibbonSettings.Edge.SMOOTH_TRANSITION && startStep > 0 ? startOffset / startStep : 0;
        float trailing = settings.trailingEdge() == RibbonSettings.Edge.SMOOTH_TRANSITION && endStep > 0 ? endOffset / endStep : 0;
        float count = last - first, scale, offset, distribution;
        switch (settings.distribution()) {
            case UNIFORM -> { scale = count / (count - (leading + trailing)); offset = -((leading / count) * scale); distribution = 1f / count; }
            case LENGTH -> {
                float leadingDistance = distance(first + 1) * leading;
                float trailingDistance = (distance(last) - distance(last - 1)) * trailing;
                scale = length / (length - (leadingDistance + trailingDistance));
                offset = -((leadingDistance / length) * scale); distribution = 1f / length;
            }
            case DISTANCE -> {
                float tileLength = (float)(settings.tileLength() * 100);
                scale = length / tileLength; offset = -(distance(first + 1) * leading / tileLength); distribution = 1f / length;
            }
            case DISTANCE_FROM_START -> { scale = length / (float)(settings.tileLength() * 100); offset = 0; distribution = 1f / length; }
            default -> throw new IllegalStateException("Unknown ribbon UV distribution");
        }
        return new Uv(scale * (float)settings.scaleU(), (offset * (float)settings.scaleU()) + (float)settings.offsetU(), distribution, false);
    }

    private float position(int row, int axis) { return particles.simulationPosition(row, axis); }
    private static float length(float x, float y, float z) { return (float)Math.sqrt((x * x + y * y) + z * z); }
}
