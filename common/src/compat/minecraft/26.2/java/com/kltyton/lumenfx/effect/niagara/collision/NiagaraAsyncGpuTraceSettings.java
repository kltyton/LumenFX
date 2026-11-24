package com.kltyton.lumenfx.effect.niagara.collision;

import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition.Provider;
import java.util.List;

/** Project-wide asynchronous ray trace provider order from NiagaraSettings. */
public record NiagaraAsyncGpuTraceSettings(List<Provider> priorities) {
    public static final NiagaraAsyncGpuTraceSettings DEFAULT = new NiagaraAsyncGpuTraceSettings(
            List.of(Provider.HARDWARE_RAY_TRACING, Provider.GLOBAL_DISTANCE_FIELD));

    public NiagaraAsyncGpuTraceSettings {
        priorities = List.copyOf(priorities);
    }

    public boolean requests(Provider requested, Provider type) {
        return requested == type || requested == Provider.DEFAULT && priorities.contains(type);
    }

    public Provider resolveSupported(Provider requested, boolean hardwareSupported, boolean distanceFieldSupported) {
        if (requested != Provider.DEFAULT)
            return supported(requested, hardwareSupported, distanceFieldSupported) ? requested : Provider.NONE;
        Provider result = Provider.NONE;
        for (Provider candidate : priorities) {
            if (!supported(candidate, hardwareSupported, distanceFieldSupported)) continue;
            if (result != Provider.NONE && result != candidate) return Provider.DEFAULT;
            result = candidate;
        }
        return result;
    }

    public Provider select(Provider requested, boolean hardwareAvailable, boolean distanceFieldAvailable) {
        for (Provider candidate : priorities) {
            if (requested != Provider.DEFAULT && requested != candidate) continue;
            if (supported(candidate, hardwareAvailable, distanceFieldAvailable)) return candidate;
        }
        return Provider.NONE;
    }

    private static boolean supported(Provider provider, boolean hardware, boolean distanceField) {
        return switch (provider) {
            case HARDWARE_RAY_TRACING -> hardware;
            case GLOBAL_DISTANCE_FIELD -> distanceField;
            case DEFAULT, NONE -> false;
        };
    }
}
