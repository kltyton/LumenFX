package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.effect.EffectDefinition;

/** Completed CPU renderer snapshot awaiting render-thread dataset upload and scene collection. */
public record CpuMeshSceneInput(EffectDefinition effect, EmitterInstance.Snapshot snapshot) { }
