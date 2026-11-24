/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.resource.PhotonAssetLibrary;

import java.util.HashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** A mesh asset reference resolved against the current resource-pack generation. */
@LDLRegisterClient(name = "resource_mesh", registry = "photon:model_source")
public final class ResourceMeshSource implements IModelSource {
    @Persisted
    private IResourcePath resourcePath = new BuiltinPath("");

    public ResourceMeshSource() {
    }

    public ResourceMeshSource(IResourcePath resourcePath) {
        this.resourcePath = resourcePath;
    }

    /** Never null — an empty {@code BuiltinPath("")} round-trips to null through the path codec. */
    public IResourcePath getResourcePath() {
        if (resourcePath == null) resourcePath = new BuiltinPath("");
        return resourcePath;
    }

    private IModelSource resolveRaw() {
        IResourcePath path = getResourcePath();
        IModelSource source = PhotonAssetLibrary.mesh(path);
        if (!(source instanceof ResourceMeshSource)) return source;
        var visited = new HashSet<IResourcePath>();
        visited.add(path);
        while (source instanceof ResourceMeshSource reference) {
            path = reference.getResourcePath();
            if (!visited.add(path)) throw new IllegalArgumentException("Cyclic Photon mesh reference: " + path);
            source = PhotonAssetLibrary.mesh(path);
        }
        return source;
    }

    @Override
    public PhotonMesh getMesh() {
        return resolveRaw().getMesh();
    }

    @Override
    public void invalidate() {
        resolveRaw().invalidate();
    }

    @Override
    public boolean hasAtlasUV() {
        return resolveRaw().hasAtlasUV();
    }

    /** A reference to an animated mesh is animated; the reference itself adds nothing either way. */
    @Override
    @Nullable
    public IDynamicMesh asDynamic() {
        return resolveRaw().asDynamic();
    }

    @Override
    @Nullable
    public VertexAnimation vertexAnimation() {
        return resolveRaw().vertexAnimation();
    }

    @Override
    public IModelSource copy() {
        return new ResourceMeshSource(getResourcePath());
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(getResourcePath(), ((ResourceMeshSource) o).getResourcePath());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getResourcePath());
    }
}
