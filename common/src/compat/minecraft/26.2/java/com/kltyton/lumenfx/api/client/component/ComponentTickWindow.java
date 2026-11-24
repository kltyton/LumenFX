package com.kltyton.lumenfx.api.client.component;

import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import java.util.Objects;

/** Tick groups of a host component supplying a Niagara input. */
public record ComponentTickWindow(ComponentTickGroup start, ComponentTickGroup end, boolean blendsPhysics) {
    public static final ComponentTickWindow DEFAULT = new ComponentTickWindow(
            ComponentTickGroup.TG_DuringPhysics, ComponentTickGroup.TG_DuringPhysics, false);

    public ComponentTickWindow {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
    }

    public ComponentTickGroup afterComponent() {
        return ComponentTickGroup.afterPrerequisite(start, end);
    }

    public ComponentTickGroup afterSkeletalPose() {
        var finalGroup = blendsPhysics ? ComponentTickGroup.latest(end, ComponentTickGroup.TG_EndPhysics) : end;
        return ComponentTickGroup.afterPrerequisite(start, finalGroup);
    }
}
