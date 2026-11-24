package com.kltyton.lumenfx.effect.render;

import java.util.Objects;

/** Niagara renderer motion policy; AutoDetect follows the imported project default. */
public record ParticleMotionSettings(Mode mode, boolean preciseByDefault) {
    public enum Mode { AUTO_DETECT, PRECISE, APPROXIMATE, DISABLE }
    public static final ParticleMotionSettings DEFAULT = new ParticleMotionSettings(Mode.AUTO_DETECT, true);
    public ParticleMotionSettings { Objects.requireNonNull(mode); }
    public boolean precise() { return mode == Mode.PRECISE || mode == Mode.AUTO_DETECT && preciseByDefault; }
    public boolean enabled() { return mode != Mode.DISABLE; }

    public static ParticleMotionSettings read(String mode, String projectDefault) {
        Mode value = switch (mode) {
            case "AutoDetect" -> Mode.AUTO_DETECT;
            case "Precise" -> Mode.PRECISE;
            case "Approximate" -> Mode.APPROXIMATE;
            case "Disable" -> Mode.DISABLE;
            default -> throw new IllegalArgumentException("Unknown Niagara motion vector mode " + mode);
        };
        if (!projectDefault.equals("Precise") && !projectDefault.equals("Approximate"))
            throw new IllegalArgumentException("Invalid Niagara project motion vector default " + projectDefault);
        return new ParticleMotionSettings(value, projectDefault.equals("Precise"));
    }
}
