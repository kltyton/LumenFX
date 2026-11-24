package com.kltyton.lumenfx.effect.niagara.scalability;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

/** Runtime IsActive evaluates quality, nearest explicit profile state, then ordered CVar responses. */
public record NiagaraPlatformSet(int qualityMask, List<ProfileState> profiles, List<Condition> conditions) {
    private static final Logger LOGGER = LogUtils.getLogger();
    public enum Response { NONE, ENABLE, DISABLE }
    public record ProfileState(String name, int enabledMask, int setMask) { }
    public record Condition(String console, Response pass, Response fail, boolean boolValue,
                            boolean minIntEnabled, int minInt, boolean maxIntEnabled, int maxInt,
                            boolean minFloatEnabled, float minFloat, boolean maxFloatEnabled, float maxFloat) {
        public boolean passes(NiagaraPlatformEnvironment environment) {
            var value = environment.console(console);
            if (value == null) {
                LOGGER.warn("Niagara platform set references an unavailable console variable '{}'", console);
                return false;
            }
            return switch (value.type()) {
                case BOOL -> (value.bits() != 0) == boolValue;
                case INT -> (!minIntEnabled || value.bits() >= minInt) && (!maxIntEnabled || value.bits() <= maxInt);
                case FLOAT -> (!minFloatEnabled || Float.intBitsToFloat(value.bits()) >= minFloat)
                        && (!maxFloatEnabled || Float.intBitsToFloat(value.bits()) <= maxFloat);
            };
        }
    }
    public static final NiagaraPlatformSet ALL = new NiagaraPlatformSet(-1, List.of(), List.of());
    public NiagaraPlatformSet { profiles = List.copyOf(profiles); conditions = List.copyOf(conditions); }
    public boolean consoleChanged(NiagaraPlatformEnvironment previous, NiagaraPlatformEnvironment next) {
        for (Condition condition : conditions)
            if (!Objects.equals(previous.console(condition.console()), next.console(condition.console()))) return true;
        return false;
    }
    public boolean active(NiagaraPlatformEnvironment environment) {
        int bit = 1 << environment.quality();
        boolean available = (environment.availableQualityMask() & bit) != 0;
        boolean enabled = available && (qualityMask & bit) != 0;
        boolean selected = false;
        for (String profile : environment.profileHierarchy()) {
            for (ProfileState state : profiles) if (SourceNames.equal(state.name(), profile)) {
                if ((state.setMask() & bit) != 0) {
                    enabled = available && (state.enabledMask() & bit) != 0;
                    selected = true;
                }
                break;
            }
            if (selected) break;
        }
        for (Condition condition : conditions) {
            Response response = condition.passes(environment) ? condition.pass() : condition.fail();
            if (response == Response.ENABLE && available) enabled = true;
            else if (response == Response.DISABLE) enabled = false;
        }
        return enabled;
    }
}
