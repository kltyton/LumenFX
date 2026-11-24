package com.kltyton.lumenfx.effect.niagara.component;

/** The source primitive's transparent draw priority and offset in Unreal centimetres. */
public record NiagaraTranslucencySort(int priority, float distanceOffsetCentimetres) {
    public static final NiagaraTranslucencySort DEFAULT = new NiagaraTranslucencySort(0, 0);

    public NiagaraTranslucencySort {
        priority = Math.clamp(priority, Short.MIN_VALUE, Short.MAX_VALUE);
        if (!Float.isFinite(distanceOffsetCentimetres)) {
            throw new IllegalArgumentException("Invalid Niagara translucency sort distance offset");
        }
    }

    /** Retains the shared renderer's squared distance scale, including offsets that place a draw behind zero. */
    public double adjustedDistanceKey(double sortDistance) {
        if (distanceOffsetCentimetres == 0) return sortDistance;
        double adjusted = Math.copySign(Math.sqrt(Math.abs(sortDistance)), sortDistance) + distanceOffsetCentimetres * .01;
        return distanceKey(adjusted);
    }

    public static double distanceKey(double distance) { return Math.copySign(distance * distance, distance); }
}
