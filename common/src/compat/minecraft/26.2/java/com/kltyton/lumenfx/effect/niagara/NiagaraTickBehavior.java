package com.kltyton.lumenfx.effect.niagara;

public enum NiagaraTickBehavior {
    UsePrereqs, UseComponentTickGroup, ForceTickFirst, ForceTickLast;

    public enum ComponentTickGroup {
        TG_PrePhysics, TG_StartPhysics, TG_DuringPhysics, TG_EndPhysics, TG_PostPhysics, TG_PostUpdateWork,
        TG_LastDemotable, TG_NewlySpawned, TG_MAX;

        public static ComponentTickGroup latest(ComponentTickGroup first, ComponentTickGroup second) {
            return first.ordinal() >= second.ordinal() ? first : second;
        }

        public static ComponentTickGroup afterPrerequisite(ComponentTickGroup start, ComponentTickGroup end) {
            int last = latest(start, end).ordinal();
            return values()[Math.min(last + 1, TG_LastDemotable.ordinal())];
        }
    }
}
