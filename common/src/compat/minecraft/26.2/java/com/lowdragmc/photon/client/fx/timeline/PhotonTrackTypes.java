package com.lowdragmc.photon.client.fx.timeline;

import com.kltyton.lumenfx.internal.photon.core.registry.LDLRegistry;
import com.lowdragmc.photon.Photon;

/** Runtime factories for the track names stored in Photon effects. */
public final class PhotonTrackTypes {
    public static final LDLRegistry.String<TrackType> REGISTRY = new LDLRegistry.String<>(Photon.id("timeline_track"));

    static {
        REGISTRY.registerOrOverride("activator", new TrackType(ActivatorTrack::new));
        REGISTRY.registerOrOverride("control", new TrackType(ControlTrack::new));
        REGISTRY.registerOrOverride("animation", new TrackType(AnimationTrack::new));
        REGISTRY.registerOrOverride("signal", new TrackType(SignalTrack::new));
        REGISTRY.registerOrOverride("group", new TrackType(TrackGroup::new));
        REGISTRY.registerOrOverride("speed", new TrackType(SpeedTrack::new));
        REGISTRY.registerOrOverride("audio", new TrackType(AudioTrack::new));
    }

    private PhotonTrackTypes() {
    }
}
