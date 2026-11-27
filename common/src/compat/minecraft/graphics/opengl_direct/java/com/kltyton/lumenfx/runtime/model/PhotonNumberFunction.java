package com.kltyton.lumenfx.runtime.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * 26.2-parity Photon {@code NumberFunction} supporting {@code constant}, {@code random_constant},
 * {@code curve} ({@code ECBCurves} explicit cubic Bezier segments), and {@code random_curve}
 * evaluated dynamically at normalized emitter cycle time or particle lifetime {@code t in [0, 1]}.
 */
public final class PhotonNumberFunction {
    public enum Mode { CONSTANT, RANDOM_CONSTANT, CURVE, RANDOM_CURVE }

    public record BezierSegment(float p0x, float p0y, float c0x, float c0y,
                                float c1x, float c1y, float p1x, float p1y) {
        public float evalY(float s) {
            float u = 1f - s;
            float b0 = u * u * u;
            float b1 = 3f * u * u * s;
            float b2 = 3f * u * s * s;
            float b3 = s * s * s;
            return b0 * p0y + b1 * c0y + b2 * c1y + b3 * p1y;
        }
    }

    private final Mode mode;
    private final float a;
    private final float b;
    private final float lower;
    private final float upper;
    private final List<BezierSegment> curves0;
    private final List<BezierSegment> curves1;
    private final float scale;

    private PhotonNumberFunction(Mode mode, float a, float b, float lower, float upper,
                                 List<BezierSegment> curves0, List<BezierSegment> curves1, float scale) {
        this.mode = mode;
        this.a = a;
        this.b = b;
        this.lower = lower;
        this.upper = upper;
        this.curves0 = curves0;
        this.curves1 = curves1;
        this.scale = scale;
    }

    public static PhotonNumberFunction constant(float value) {
        return new PhotonNumberFunction(Mode.CONSTANT, value, value, value, value, List.of(), List.of(), 1f);
    }

    public static PhotonNumberFunction randomConstant(float a, float b) {
        return new PhotonNumberFunction(Mode.RANDOM_CONSTANT, a, b, Math.min(a, b), Math.max(a, b), List.of(), List.of(), 1f);
    }

    public static PhotonNumberFunction curve(float lower, float upper, List<BezierSegment> segments) {
        if (segments == null || segments.isEmpty()) return constant(0.5f * (lower + upper));
        return new PhotonNumberFunction(Mode.CURVE, lower, upper, lower, upper, List.copyOf(segments), List.of(), 1f);
    }

    public static PhotonNumberFunction randomCurve(float lower, float upper,
                                                   List<BezierSegment> curves0, List<BezierSegment> curves1) {
        if ((curves0 == null || curves0.isEmpty()) && (curves1 == null || curves1.isEmpty())) {
            return constant(0.5f * (lower + upper));
        }
        if (curves1 == null || curves1.isEmpty()) return curve(lower, upper, curves0);
        if (curves0 == null || curves0.isEmpty()) return curve(lower, upper, curves1);
        return new PhotonNumberFunction(Mode.RANDOM_CURVE, lower, upper, lower, upper,
                List.copyOf(curves0), List.copyOf(curves1), 1f);
    }

    public PhotonNumberFunction scaled(float factor) {
        if (factor == 1f) return this;
        return new PhotonNumberFunction(mode, a, b, lower, upper, curves0, curves1, scale * factor);
    }

    public Mode mode() { return mode; }

    public float evaluate(float t, float randomFraction) {
        float clampedT = Math.max(0f, Math.min(1f, t));
        float clampedR = Math.max(0f, Math.min(1f, randomFraction));
        float raw = switch (mode) {
            case CONSTANT -> a;
            case RANDOM_CONSTANT -> {
                float lo = Math.min(a, b);
                float hi = Math.max(a, b);
                yield lo == hi ? hi : (lo + clampedR * (hi - lo));
            }
            case CURVE -> lower + (upper - lower) * evalCurvesY(curves0, clampedT);
            case RANDOM_CURVE -> {
                float y0 = evalCurvesY(curves0, clampedT);
                float y1 = evalCurvesY(curves1, clampedT);
                float y = y0 == y1 ? y0 : (Math.min(y0, y1) + clampedR * Math.abs(y0 - y1));
                yield lower + (upper - lower) * y;
            }
        };
        return raw * scale;
    }

    /** Deterministic representative value at t = 0, midpoint random = 0.5 for static inspection/compatibility. */
    public float initialValue() {
        return evaluate(0f, 0.5f);
    }

    public static float evalCurvesY(List<BezierSegment> segments, float x) {
        if (segments.isEmpty()) return 0f;
        BezierSegment first = segments.get(0);
        if (x < first.p0x()) return first.p0y();
        for (int i = 0; i < segments.size(); i++) {
            BezierSegment seg = segments.get(i);
            if (x >= seg.p0x() && x <= seg.p1x()) {
                float dx = seg.p1x() - seg.p0x();
                return dx <= 0f ? seg.p1y() : seg.evalY((x - seg.p0x()) / dx);
            }
        }
        return segments.get(segments.size() - 1).p1y();
    }

    @SuppressWarnings("unchecked")
    public static PhotonNumberFunction parse(@Nullable Object obj, float fallback) {
        if (obj instanceof Number n) return constant(n.floatValue());
        if (!(obj instanceof Map<?, ?> rawMap)) return constant(fallback);
        Map<String, Object> m = (Map<String, Object>) rawMap;
        if (m.get("number") instanceof Number n) return constant(n.floatValue());

        String type = m.get("type") instanceof String s ? s.toLowerCase(Locale.ROOT) : "";
        Map<String, Object> d = m.get("data") instanceof Map<?, ?> dm ? (Map<String, Object>) dm : m;

        if (d.get("number") instanceof Number n && !type.contains("curve") && !type.contains("random")) {
            return constant(n.floatValue());
        }
        if (d.get("a") instanceof Number aVal && d.get("b") instanceof Number bVal) {
            return randomConstant(aVal.floatValue(), bVal.floatValue());
        }
        if (d.get("number1") instanceof Number aVal && d.get("number2") instanceof Number bVal) {
            return randomConstant(aVal.floatValue(), bVal.floatValue());
        }
        boolean hasCurves0 = d.containsKey("curves") || d.containsKey("curves0");
        boolean hasCurves1 = d.containsKey("curves1");
        if (hasCurves0 || hasCurves1) {
            float min = readFloat(d, "min", 0f);
            float max = readFloat(d, "max", 1f);
            float lo = d.containsKey("lower") ? readFloat(d, "lower", min) : min;
            float hi = d.containsKey("upper") ? readFloat(d, "upper", max) : max;
            List<BezierSegment> c0 = parseSegments(d.containsKey("curves0") ? d.get("curves0") : d.get("curves"));
            List<BezierSegment> c1 = hasCurves1 ? parseSegments(d.get("curves1")) : List.of();
            if (!c0.isEmpty() && !c1.isEmpty()) return randomCurve(lo, hi, c0, c1);
            if (!c0.isEmpty()) return curve(lo, hi, c0);
            if (!c1.isEmpty()) return curve(lo, hi, c1);
            return constant(0.5f * (lo + hi));
        }
        if (d.get("number") instanceof Number n) {
            return constant(n.floatValue());
        }
        if (d.get("min") instanceof Number lo && d.get("max") instanceof Number hi) {
            return randomConstant(lo.floatValue(), hi.floatValue());
        }
        return constant(fallback);
    }

    @SuppressWarnings("unchecked")
    private static List<BezierSegment> parseSegments(@Nullable Object curvesObj) {
        List<?> rawList = unwrapList(curvesObj);
        if (rawList == null || rawList.isEmpty()) return List.of();
        List<BezierSegment> out = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            List<?> pts = null;
            if (item instanceof List<?> list) {
                pts = list;
            } else if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> fm = (Map<String, Object>) rawMap;
                Object cand = fm.containsKey("coords") ? fm.get("coords")
                        : (fm.containsKey("data") ? fm.get("data") : fm.get("points"));
                if (cand instanceof List<?> list) pts = list;
            }
            if (pts == null) continue;
            if (pts.size() >= 8) {
                out.add(new BezierSegment(
                        asFloat(pts.get(0)), asFloat(pts.get(1)),
                        asFloat(pts.get(2)), asFloat(pts.get(3)),
                        asFloat(pts.get(4)), asFloat(pts.get(5)),
                        asFloat(pts.get(6)), asFloat(pts.get(7))));
            } else if (pts.size() >= 4) {
                float p0x = asFloat(pts.get(0)), p0y = asFloat(pts.get(1));
                float p1x = asFloat(pts.get(2)), p1y = asFloat(pts.get(3));
                out.add(new BezierSegment(
                        p0x, p0y,
                        p0x + (p1x - p0x) / 3f, p0y + (p1y - p0y) / 3f,
                        p0x + 2f * (p1x - p0x) / 3f, p0y + 2f * (p1y - p0y) / 3f,
                        p1x, p1y));
            } else if (pts.size() >= 2) {
                float y = asFloat(pts.get(1));
                out.add(new BezierSegment(0f, y, 1f / 3f, y, 2f / 3f, y, 1f, y));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<?> unwrapList(@Nullable Object obj) {
        if (obj instanceof List<?> list) return list;
        if (obj instanceof Map<?, ?> rawMap) {
            Map<String, Object> m = (Map<String, Object>) rawMap;
            if (m.get("payload") instanceof List<?> payload) return payload;
            if (m.get("segments") instanceof List<?> segments) return segments;
        }
        return null;
    }

    private static float asFloat(Object v) {
        return v instanceof Number n ? n.floatValue() : 0f;
    }

    private static float readFloat(Map<String, Object> map, String key, float fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.floatValue() : fallback;
    }
}
