package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;

/** Bindings and owner transforms prepare one parameter snapshot before an analytic module executes. */
public interface PreparedModule extends StatelessParticleSimulation.Module {
    void prepare(int[] parameters, float[] sourcePose);
}
