package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.api.client.EffectHandle.ParameterType;
import com.kltyton.lumenfx.api.client.EffectHandle.ParameterValue;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.Source;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs.DistanceField;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshWorlds.Lease;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** A live Static Mesh Indirect reference that may be shared with Niagara systems or data channels. */
public final class NiagaraStaticMeshReference implements AutoCloseable {
    private final Lease lease;

    private NiagaraStaticMeshReference(Lease lease) { this.lease = lease; }

    /** The component identity belongs to the host and must remain live for as long as the mesh exists. */
    public static NiagaraStaticMeshReference acquire(ClientLevel world, Object componentIdentity, Source source) {
        var client = Minecraft.getInstance();
        if (!client.isSameThread() || client.level != Objects.requireNonNull(world))
            throw new IllegalStateException("Niagara mesh references require the current client world thread");
        return new NiagaraStaticMeshReference(NiagaraStaticMeshWorlds.acquire(world,
                Objects.requireNonNull(componentIdentity), Objects.requireNonNull(source)));
    }

    public NiagaraStaticMeshReference retain() { return new NiagaraStaticMeshReference(lease.retain()); }
    public boolean isAlive() { return lease.alive(); }
    public void update(Source source) { lease.update(Objects.requireNonNull(source)); }
    public ParameterValue parameter() {
        return new ParameterValue(ParameterType.STATIC_MESH_ID, new long[] {lease.index(), lease.acquireTag()});
    }
    public List<Number> channelField() { return List.of(lease.index(), lease.acquireTag()); }
    public DistanceField distanceField() { return new DistanceField(lease.index(), lease.acquireTag()); }
    @Override public void close() { lease.close(); }
}
