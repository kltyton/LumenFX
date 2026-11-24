package com.kltyton.lumenfx.effect.niagara.scalability;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record NiagaraSystemScalability(String effectType, Frequency frequency, Reaction reaction,
                                       @Nullable String significanceClass, boolean allowLocalPlayerCulling, boolean scriptReadsSignificance,
                                       List<Row> effectSettings, List<Row> overrides) {
    public enum Frequency { SpawnOnly, Low, Medium, High, Continuous }

    public enum Reaction {
        Deactivate, DeactivateImmediate, DeactivateResume, DeactivateImmediateResume, PauseResume;

        public boolean canPreCull() { return this == Deactivate || this == DeactivateImmediate; }
    }

    public record Distance(boolean enabled, float limit) { }
    public record Count(boolean enabled, int limit) { }
    public record Visibility(boolean notRendered, float maxTimeWithoutRender, boolean outsideFrustum,
                             boolean allowFrustumPrecull, float maxTimeOutsideFrustum) { }

    public record Ramp(float startX, float startY, float endX, float endY) {
        public float evaluate(float x) {
            if (x <= startX) return startY;
            if (x >= endX) return endY;
            return startY + (endY - startY) * ((x - startX) / (endX - startX));
        }
    }

    public record Budget(boolean cull, float maxUsage, @Nullable Ramp distanceScale,
                         @Nullable Ramp effectInstancesScale, @Nullable Ramp systemInstancesScale) { }

    public enum ProxyMode { None, Instanced_Rendered }
    public record Proxy(ProxyMode mode, int maximum) { }

    public record Row(NiagaraPlatformSet platforms, @Nullable Distance distance, @Nullable Count effectInstances,
                      @Nullable Count systemInstances, @Nullable Visibility visibility, @Nullable Budget budget,
                      @Nullable Proxy proxy) { }

    public record Resolved(Distance distance, Count effectInstances, Count systemInstances, Visibility visibility,
                           Budget budget, Proxy proxy) {
        private static final Count NO_COUNT = new Count(false, 0);
        public static final Resolved DEFAULT = new Resolved(new Distance(false, 0), NO_COUNT, NO_COUNT,
                new Visibility(false, 1, false, false, 1), new Budget(false, 1, null, null, null), new Proxy(ProxyMode.None, 32));

        Resolved apply(Row row) {
            return new Resolved(row.distance() == null ? distance : row.distance(),
                    row.effectInstances() == null ? effectInstances : row.effectInstances(),
                    row.systemInstances() == null ? systemInstances : row.systemInstances(),
                    row.visibility() == null ? visibility : row.visibility(), row.budget() == null ? budget : row.budget(),
                    row.proxy() == null ? proxy : row.proxy());
        }
    }

    public NiagaraSystemScalability {
        effectSettings = List.copyOf(effectSettings);
        overrides = List.copyOf(overrides);
    }

    public Resolved resolve(NiagaraPlatformEnvironment platform) {
        Resolved resolved = Resolved.DEFAULT;
        for (var row : effectSettings) {
            if (!row.platforms().active(platform)) continue;
            resolved = resolved.apply(row);
            break;
        }
        for (var row : overrides) {
            if (!row.platforms().active(platform)) continue;
            resolved = resolved.apply(row);
            break;
        }
        var distance = resolved.distance();
        float minimum = platform.minimumSystemScalabilityDistance();
        return new Resolved(new Distance(distance.enabled(), Math.max(minimum, distance.limit())),
                resolved.effectInstances(), resolved.systemInstances(), resolved.visibility(), resolved.budget(), resolved.proxy());
    }
}
