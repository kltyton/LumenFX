package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import org.jspecify.annotations.Nullable;

/** CPU particle readers use dataset indices independently of the backend's persistent-ID capabilities. */
interface NiagaraParticleReadSource {
    VectorDataSet particleReadData(boolean previous);
    int[] particleReadIndex(boolean previous);
    int[] eventParticleReadIndex();
    int spawnedParticleCount();
    int spawnedParticleId(int index);
    int spawnedParticleTag();
    int frameSpawned(boolean previous);
    boolean particleReadLocalSpace();
    String particleReadName();
    NiagaraDefinition.@Nullable Attribute particleReadAttribute(String name, String type);
    int particleIndex(VectorDataSet data, int[] table, int id, int acquireTag);
}
