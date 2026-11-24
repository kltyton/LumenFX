package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeshDrawSelectionTest {
    @Test
    void sortsSourceBoundsInTheSameWorldSpaceAsTheCamera() {
        var pose = EffectHandle.Pose.at(new Vec3(4, 8, 12));
        var bounds = new NiagaraBounds(50, 150, 250, 150, 250, 350);
        var camera = new Vec3(2, 5, 11);

        assertEquals(54.0, MeshDrawSelection.depth(pose, bounds, camera), 1.0e-9);
    }
}
