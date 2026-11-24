package com.kltyton.lumenfx.effect.unity;

/** An authored link from a particle event to a child system in the same effect. */
public record UnitySubEmitter(String target, Event event, double probability, int inheritance) {
    public enum Event { BIRTH, COLLISION, DEATH, TRIGGER, MANUAL }

    public static final int COLOR = 1;
    public static final int SIZE = 1 << 1;
    public static final int ROTATION = 1 << 2;
    public static final int LIFETIME = 1 << 3;
    public static final int DURATION = 1 << 4;
    private static final int ALL = COLOR | SIZE | ROTATION | LIFETIME | DURATION;

    public UnitySubEmitter {
        if (target.isBlank() || event == null || !Double.isFinite(probability) || probability < 0 || probability > 1
                || (inheritance & ~ALL) != 0)
            throw new IllegalArgumentException("Invalid Unity sub-emitter link: " + target);
    }

    public boolean inherits(int property) { return (inheritance & property) != 0; }
}
