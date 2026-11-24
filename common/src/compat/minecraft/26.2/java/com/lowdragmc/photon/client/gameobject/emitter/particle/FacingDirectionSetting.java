/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.particle;

import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSelector;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import lombok.EqualsAndHashCode;
import org.joml.Vector3f;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FacingDirectionSetting implements IConfigurable, IPersistedSerializable {

    public final ParticleRendererSetting particleRendererSetting;

    public FacingDirectionSetting(ParticleRendererSetting particleRendererSetting) {
        this.particleRendererSetting = particleRendererSetting;
    }

    public enum Mode {
        DERIVE_FROM_VELOCITY,
        CUSTOM_DIRECTION
    }

    @Configurable(name = "FacingDirectionSetting.mode", tips = "photon.emitter.config.renderer.facingDirection.mode")
    @ConfigSelector(subConfiguratorBuilder = "buildModeConfigurator")
    @Persisted
    @EqualsAndHashCode.Include
    private Mode mode = Mode.DERIVE_FROM_VELOCITY;
    @Persisted
    @EqualsAndHashCode.Include
    private float minSpeedThreshold = 0.01f;
    @Persisted
    @EqualsAndHashCode.Include
    private Vector3f customDirection = new Vector3f(0, 1, 0);

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        if (mode == this.mode) return;
        this.mode = mode;
        particleRendererSetting.getConfig().particleRenderType.clearInstance();
    }

    public float getMinSpeedThreshold() {
        return minSpeedThreshold;
    }

    public void setMinSpeedThreshold(float minSpeedThreshold) {
        if (minSpeedThreshold == this.minSpeedThreshold) return;
        this.minSpeedThreshold = minSpeedThreshold;
        particleRendererSetting.getConfig().particleRenderType.clearInstance();
    }

    public Vector3f getCustomDirection() {
        return customDirection;
    }

    public void setCustomDirection(Vector3f customDirection) {
        if (customDirection.equals(this.customDirection)) return;
        this.customDirection = customDirection;
        particleRendererSetting.getConfig().particleRenderType.clearInstance();
    }

    @Override
    public void afterDeserialize() {
        IPersistedSerializable.super.afterDeserialize();
        particleRendererSetting.getConfig().particleRenderType.clearInstance();
    }
}
