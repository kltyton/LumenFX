/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.fx;

import com.lowdragmc.photon.client.gameobject.IFXObject;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * The context an {@link com.lowdragmc.photon.client.fx.FXRuntime} plays inside: supplies the level
 * and receives per-tick / per-frame callbacks from every emitted {@link IFXObject}.
 * <p>
 * Implementations anchor the FX to something and keep it in sync (or kill it): e.g.
 * {@link EntityEffectExecutor} follows an entity, {@link BlockEffectExecutor} a block, and the
 * world playback can provide an explicit seed.
 */
public interface IEffectExecutor {

    Level getLevel();

    /**
     * update each FX objects during their duration, per tick. Execute low frequency logic here.
     * <br>
     * e.g., kill particle
     * @param fxObject fx object
     */
    default void updateFXObjectTick(IFXObject fxObject) {
    }

    /**
     * update each FX objects during rendering, per frame. Execute high frequency logic here.
     * <br>
     * e.g., update emitter position, rotation, scale
     * @param fxObject fx object
     * @param partialTicks partialTicks
     */
    default void updateFXObjectFrame(IFXObject fxObject, float partialTicks) {

    }

    default RandomSource getRandomSource() {
        return getLevel().getRandom();
    }

    /** Whether the host has a fresh source sample for new births on this step. */
    default boolean canEmit(IFXObject object) {
        return true;
    }

    /** The event was captured on an earlier tick and its effect may have been retired since then. */
    default boolean acceptsSubEffects() { return true; }

    default void trackSubEffectLoad(CompletableFuture<?> load) { }

    default void trackSubEffect(FXRuntime runtime) { }

    /**
     * The entity this effect belongs to, if any: beam raycasts skip it, and so does particle collision while its
     * "ignore owner" option is on, so a mob's own effect does not bounce off the mob. Game thread.
     */
    @Nullable
    default Entity owner() {
        return null;
    }

    /**
     * Called when a timeline {@code signal} track fires a signal during live forward playback. Override
     * to react per-effect; global subscribers can also listen via {@code PhotonSignals}.
     *
     * @param channel the signal track's display name (channel)
     * @param name    the signal's name (may repeat across signals)
     * @param data    the signal's custom data
     * @param time    the master-clock tick at which it fired
     */
    default void onTimelineSignal(String channel, String name, CompoundTag data, double time) {
    }
}
