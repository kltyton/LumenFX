package com.kltyton.lumenfx.effect.render;

/** Conventional near-to-far comparison; the renderer maps it to the host's depth convention. */
public record MaterialDepth(Compare compare, boolean write) {
    public enum Compare { DISABLED, NEVER, LESS, EQUAL, LESS_EQUAL, GREATER, NOT_EQUAL, GREATER_EQUAL, ALWAYS }

    public MaterialDepth {
        if (compare == null) throw new IllegalArgumentException("Missing material depth comparison");
    }

    public static MaterialDepth legacy(boolean test, boolean opaque) {
        return new MaterialDepth(test ? Compare.LESS_EQUAL : Compare.ALWAYS, test && opaque);
    }

    public boolean tests() { return compare != Compare.DISABLED && compare != Compare.ALWAYS; }
}
