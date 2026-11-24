package com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom;

import com.kltyton.lumenfx.api.client.groom.NiagaraGroomInputs;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomInputs.Group;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Collision;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Forces;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Scales;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Spring;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Strand;
import com.kltyton.lumenfx.effect.niagara.groom.HairStrandsDefinition;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.jspecify.annotations.Nullable;

public final class NiagaraHairStrandsState {
    @FunctionalInterface
    public interface Output { void set(int word, int lane, int value); }

    private static final NiagaraGroomPhysics INITIAL = new NiagaraGroomPhysics(0, 5, 20, 0,
            new Forces(new Vec3(0, 0, -981), 0.1f, Vec3.ZERO),
            new Spring(true, false, 0.01f, 0.01f), new Spring(true, false, 0.01f, 1),
            new Collision(true, true, 0.1f, 0.1f, 1, 30, 30, 30, 1),
            new Strand(1, 0.1f, 0.01f), Scales.unit());

    private final HairStrandsDefinition definition;
    private final GroomTransforms transforms = new GroomTransforms();
    private NiagaraGroomPhysics physics = INITIAL;
    private @Nullable Group group;
    private @Nullable String identity;
    private long skeletalMeshes;
    private int strands;
    private boolean globalInterpolation;
    private boolean reset;
    private float timeDilation = 1;

    public NiagaraHairStrandsState(HairStrandsDefinition definition) {
        this.definition = definition;
    }

    public void step(NiagaraGroomInputs inputs, float delta) {
        Group next = definition.sourceActor().isEmpty() ? inputs.attached() : inputs.actors().get(definition.sourceActor());
        if (next == null || next.guides() == null) {
            group = null;
            return;
        }
        if (!next.simulationEnabled()) {
            group = null;
            identity = null;
            strands = 0;
            physics = INITIAL;
            globalInterpolation = false;
            timeDilation = 1;
            transforms.clear();
            return;
        }

        reset = group == null || !next.identity().equals(identity) || next.guides() != group.guides()
                || next.forceReset() || next.skeletalMeshes() != skeletalMeshes;
        group = next;
        identity = next.identity();
        skeletalMeshes = next.skeletalMeshes();
        strands = next.guides().curves();
        physics = next.physics();
        globalInterpolation = next.globalInterpolation();
        timeDilation = next.timeDilation();
        transforms.update(next, reset, delta);
    }

    public void execute(String function, int lanes, Output output) {
        int[] values = switch (function) {
            case "GetNumStrands" -> words(strands);
            case "GetStrandSize" -> words(physics.strandSize());
            case "GetSubSteps" -> words(physics.subSteps());
            case "GetIterationCount" -> words(physics.iterationCount());
            case "GetGravityVector" -> vector(physics.forces().gravity());
            case "GetGravityPreloading" -> scalar(physics.gravityPreloading());
            case "GetAirDrag" -> scalar(physics.forces().airDrag());
            case "GetAirVelocity" -> vector(physics.forces().airVelocity());
            case "GetSolveBend" -> flag(physics.bend().solve());
            case "GetProjectBend" -> flag(physics.bend().project());
            case "GetBendDamping" -> scalar(physics.bend().damping());
            case "GetBendStiffness" -> scalar(physics.bend().stiffness());
            case "GetSolveStretch" -> flag(physics.stretch().solve());
            case "GetProjectStretch" -> flag(physics.stretch().project());
            case "GetStretchDamping" -> scalar(physics.stretch().damping());
            case "GetStretchStiffness" -> scalar(physics.stretch().stiffness());
            case "GetSolveCollision" -> flag(physics.collision().solve());
            case "GetProjectCollision" -> flag(physics.collision().project());
            case "GetStaticFriction" -> scalar(physics.collision().staticFriction());
            case "GetKineticFriction" -> scalar(physics.collision().kineticFriction());
            case "GetStrandsViscosity" -> scalar(physics.collision().viscosity());
            case "GetGridDimension" -> vector(new Vec3(physics.collision().gridX(), physics.collision().gridY(), physics.collision().gridZ()));
            case "GetCollisionRadius" -> scalar(physics.collision().radius());
            case "GetStrandsDensity" -> scalar(physics.strand().density());
            case "GetTimeDilation" -> scalar(timeDilation);
            case "GetStrandsSmoothing" -> scalar(physics.strand().smoothing());
            case "GetStrandsThickness" -> scalar(physics.strand().thickness());
            case "GetWorldTransform" -> matrix(transforms.component());
            case "GetWorldInverse" -> matrix(GroomTransforms.inverse(transforms.component()));
            case "HasGlobalInterpolation" -> flag(globalInterpolation);
            default -> throw new IllegalArgumentException("Unknown Niagara HairStrands CPU result " + function);
        };
        for (int lane = 0; lane < lanes; lane++) {
            for (int word = 0; word < values.length; word++) output.set(word, lane, values[word]);
        }
    }

    public HairStrandsFrame frame() {
        return new HairStrandsFrame(group, transforms, reset);
    }

    private static int[] words(int... values) { return values; }
    private static int[] flag(boolean value) { return words(value ? 1 : 0); }
    private static int[] scalar(double value) { return words(bits(value)); }
    private static int[] vector(Vec3 value) { return words(bits(value.x), bits(value.y), bits(value.z)); }
    private static int bits(double value) { return Float.floatToRawIntBits((float) value); }

    private static int[] matrix(Matrix4dc value) {
        var words = new int[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) words[4 * row + column] = bits(value.get(row, column));
        }
        return words;
    }
}
