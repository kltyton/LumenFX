package com.lowdragmc.photon.utils;

import net.neoforged.neoforge.common.util.ValueIOSerializable;

/** Extends NeoForge's ValueIOSerializable when external LDLib2 is present so PersistedParser detects it natively. */
public interface PhotonValueSerializable extends ValueIOSerializable {
}
