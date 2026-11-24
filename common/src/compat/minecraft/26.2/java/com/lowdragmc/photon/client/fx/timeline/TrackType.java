package com.lowdragmc.photon.client.fx.timeline;

import java.util.function.Supplier;

public final class TrackType {
    private final Supplier<? extends Track> creator;

    public TrackType(Supplier<? extends Track> creator) {
        this.creator = creator;
    }

    public String name() {
        return PhotonTrackTypes.REGISTRY.getKey(this);
    }

    public Track create() {
        var track = creator.get();
        track.setType(this);
        return track;
    }
}
