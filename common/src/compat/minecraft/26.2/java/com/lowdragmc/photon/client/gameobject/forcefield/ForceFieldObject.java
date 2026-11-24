/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.forcefield;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import org.joml.Vector3f;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/**
 * A Unity-style particle force field: affects the particles of every sibling emitter (same FX scene)
 * whose External Forces module is enabled. Supports a shaped influence volume with range falloff,
 * directional force, gravity toward a focus point, vortex rotation, and drag.
 */
@ParametersAreNonnullByDefault
public class ForceFieldObject extends FXObject {
    /** Matches the 0.05f authored-force scale of {@code ForceOverLifetimeSetting.Runtime#getForce}. */
    private static final float FORCE_SCALE = 0.05f;

    @LDLRegisterClient(name = "force_field", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new ForceFieldObject();
        }

        @Override
        public List<RuntimeBinding> runtimeBindings() {
            return RUNTIME_BINDINGS;
        }
    };

    public static final List<RuntimeBinding> RUNTIME_BINDINGS = List.of(
            new RuntimeBinding("startRange", "ForceFieldConfig.startRange", ConfigValueType.FLOAT,
                    o -> ((ForceFieldObject) o).runtime().startRange),
            new RuntimeBinding("endRange", "ForceFieldConfig.endRange", ConfigValueType.FLOAT,
                    o -> ((ForceFieldObject) o).runtime().endRange),
            new RuntimeBinding("directionX", "ForceFieldConfig.directionX", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().directionX),
            new RuntimeBinding("directionY", "ForceFieldConfig.directionY", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().directionY),
            new RuntimeBinding("directionZ", "ForceFieldConfig.directionZ", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().directionZ),
            new RuntimeBinding("gravity", "ForceFieldConfig.gravity", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().gravity),
            new RuntimeBinding("gravityFocus", "ForceFieldConfig.gravityFocus", ConfigValueType.FLOAT,
                    o -> ((ForceFieldObject) o).runtime().gravityFocus),
            new RuntimeBinding("rotationSpeed", "ForceFieldConfig.rotationSpeed", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().rotationSpeed),
            new RuntimeBinding("rotationAttraction", "ForceFieldConfig.rotationAttraction", ConfigValueType.FLOAT,
                    o -> ((ForceFieldObject) o).runtime().rotationAttraction),
            new RuntimeBinding("drag", "ForceFieldConfig.drag", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((ForceFieldObject) o).runtime().drag));

    @Persisted(subPersisted = true)
    public final ForceFieldConfig config = new ForceFieldConfig();

    /** Per-instance runtime layer: named override slots (timeline-driven) over the immutable config. */
    private ForceFieldConfig.Runtime runtime;

    // stable per-field keys for the per-particle vortex-axis randomness (memoized on the particle)
    private final Object rotationRandomKeyX = new Object();
    private final Object rotationRandomKeyZ = new Object();

    public ForceFieldConfig.Runtime runtime() {
        if (runtime == null) {
            runtime = new ForceFieldConfig.Runtime(config);
        }
        return runtime;
    }

    @Override
    public FXObjectType getFXObjectType() {
        return TYPE;
    }

    /** Whether this field has been removed (leaf objects can't use the child-based {@code isAlive}). */
    public boolean isRemoved() {
        return this.removed;
    }

    @Override
    public void reset() {
        super.reset();
        if (runtime != null) {
            runtime.clear();
        }
    }

    @Override
    protected void onTickBegin() {
        // Transform's lazy matrix caches are not synchronized; warm them on the game thread
        // before particles read them via parallelStream.
        transform().localToWorldMatrix();
        transform().worldToLocalMatrix();
        transform().rotation();
    }

    /**
     * Evaluate this field for one particle and fold the result into {@code worldVelocity} (mutated in
     * place: direction/gravity/vortex integrate as force * dt, drag damps). Thread-safe: read-only on
     * the field's state; per-particle randoms are memoized on the particle.
     *
     * @param worldPos      the particle's world position
     * @param worldVelocity the particle's stored velocity in world space (in/out)
     * @param particleSize  the particle's current size (max component)
     * @param dt            step in ticks
     * @param particle      the affected particle (lifetime t + memoized randoms)
     * @param multiplier    the emitter's external-forces multiplier for this particle
     */
    public void apply(Vector3f worldPos, Vector3f worldVelocity, float particleSize, float dt, IParticle particle, float multiplier) {
        var rt = runtime();
        // shape membership + falloff are tested in field-local space, so the field's transform
        // (including scale) shapes the influence volume
        var local = new Vector3f(worldPos).mulPosition(transform().worldToLocalMatrix());
        var endRange = rt.getEndRange();
        float influence = ForceFieldResponse.influence(rt.getShape(), rt.getStartRange(), endRange, endRange, local);
        float strength = influence * multiplier;
        if (strength == 0) {
            return;
        }

        var t = particle.getT();
        var localToWorld = transform().localToWorldMatrix();

        // directional force, authored along the field's local axes
        var scratch = new Vector3f(
                rt.getDirectionX(particle, t),
                rt.getDirectionY(particle, t),
                rt.getDirectionZ(particle, t));
        ForceFieldResponse.direction(localToWorld, scratch, worldVelocity, FORCE_SCALE * strength * dt);

        // gravity: pull toward the focus point (0 = field center, 1 = a point at endRange toward the particle)
        var gravity = rt.getGravity(particle, t);
        ForceFieldResponse.gravity(localToWorld, local, worldVelocity, scratch,
                gravity, rt.getGravityFocus() * endRange, FORCE_SCALE, strength, dt);

        // vortex rotation around the field's local Y axis; randomness offsets the axis anchor per particle
        var rotationSpeed = rt.getRotationSpeed(particle, t);
        if (rotationSpeed != 0) {
            var anchorX = config.getRotationRandomnessX() * (particle.getMemRandom(rotationRandomKeyX, rand -> rand.nextFloat() * 2 - 1));
            var anchorZ = config.getRotationRandomnessZ() * (particle.getMemRandom(rotationRandomKeyZ, rand -> rand.nextFloat() * 2 - 1));
            ForceFieldResponse.vortex(localToWorld, local, worldVelocity, scratch, anchorX, anchorZ,
                    rotationSpeed * FORCE_SCALE, strength * dt, rt.getRotationAttraction() * strength * dt);
        }

        // drag: velocity damping, optionally scaled by particle size and speed
        var drag = rt.getDrag(particle, t);
        if (drag > 0) {
            ForceFieldResponse.drag(worldVelocity, particleSize, drag * strength, FORCE_SCALE, dt,
                    config.isMultiplyDragByParticleSize(), config.isMultiplyDragByParticleVelocity());
        }
    }
}
