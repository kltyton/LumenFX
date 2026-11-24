package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.EffectHandle.Input;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.client.transform.NiagaraComponentBounds;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic.NiagaraDynamicMeshRenderSource;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** Shared CPU/GPU material slots and component-level LOD policy. */
public final class MeshDrawSelection {
    private MeshDrawSelection() { }

    /** The regular mesh renderer's host index addresses the expanded draw records. */
    public static boolean selectsDraw(@Nullable Integer selection, int drawIndex) {
        return selection == null || selection == -1 || selection == drawIndex;
    }

    public static double depth(EffectHandle.Pose pose, NiagaraBounds bounds, Vec3 camera) {
        return depth(pose, bounds, camera, null);
    }

    public static double depth(EffectHandle.Pose pose, NiagaraBounds bounds, Vec3 camera, @Nullable MeshView view) {
        Vec3 sourceCenter = NiagaraComponentBounds.center(pose, bounds);
        Vec3 relative = new Vec3(sourceCenter.x * .01, sourceCenter.z * .01, sourceCenter.y * .01).subtract(camera);
        return view == null ? relative.lengthSqr() : view.translucentSortKey(relative);
    }

    public static boolean needsPreviousPayload(MaterialDefinition material) {
        return material.writesVelocity();
    }

    public static java.util.List<MeshRendererSettings.Entry> entries(MeshRendererSettings settings, EffectHandle.Input input) {
        var value = parameter(input, settings.meshesBinding());
        return value instanceof RendererParameter.Meshes meshes ? meshes.values() : settings.entries();
    }

    public static boolean translucent(EffectDefinition effect, EmitterDefinition definition, EffectHandle.Input input) {
        var settings = java.util.Objects.requireNonNull(definition.meshSettings());
        for (var configured : entries(settings, input)) {
            var mesh = entry(configured, input, settings);
            var asset = mesh.asset();
            if (asset == null) continue;
            for (var lod : asset.lods()) for (var section : lod.sections())
                if (material(effect, definition, asset, mesh, section, input).blend().translucent()) return true;
        }
        return false;
    }

    public static MeshRendererSettings.Entry entry(MeshRendererSettings.Entry entry, EffectHandle.Input input,
                                                    MeshRendererSettings settings) {
        if (settings.nanite() == null) return entry(entry, input);
        var resolved = nativeNaniteEntry(entry, input);
        String format = NiagaraPlatforms.snapshot().shaderPlatforms().featureLevel();
        return drawable(resolved.asset(), format) ? resolved : withAsset(resolved, null);
    }

    public static MeshRendererSettings.Entry entry(MeshRendererSettings.Entry entry, EffectHandle.Input input) {
        var value = parameter(input, entry.assetBinding());
        String format = NiagaraPlatforms.snapshot().shaderPlatforms().featureLevel();
        var asset = entry.asset();
        if (value instanceof RendererParameter.Mesh mesh && drawable(mesh.value(), format)) asset = mesh.value();
        return withAsset(entry, drawable(asset, format) ? asset : null);
    }

    public static MeshRendererSettings.Entry nativeNaniteEntry(MeshRendererSettings.Entry entry, EffectHandle.Input input) {
        if (entry.assetBinding() == null) return entry;
        var value = parameter(input, entry.assetBinding());
        return withAsset(entry, value instanceof RendererParameter.Mesh mesh ? mesh.value() : null);
    }

    private static MeshRendererSettings.Entry withAsset(MeshRendererSettings.Entry entry, @Nullable StaticMeshAsset asset) {
        if (asset == entry.asset()) return entry;
        return new MeshRendererSettings.Entry(asset, entry.assetBinding(), entry.materials(), entry.scale(), entry.rotation(),
                entry.pivot(), entry.pivotSpace(), entry.lodMode(), entry.lodLevel(), entry.lodBias(), entry.lodDistanceFactor(),
                entry.useLodRange(), entry.minimumLod(), entry.maximumLod());
    }

    private static @Nullable RendererParameter parameter(@Nullable Input input, @Nullable String name) {
        return input == null || name == null ? null : RendererParameter.named(input.rendererParameters(), name);
    }

    public static void requestStreamingLods(EffectDefinition effect, EmitterDefinition renderer, EffectHandle.Input input,
                                            MeshView view, Vec3 camera, Vec3 ownerCentre, boolean recentlyRendered) {
        var settings = java.util.Objects.requireNonNull(renderer.meshSettings());
        var lodView = view.withLodPolicy(effect.materials().get(renderer.material()).renderSettings().meshLod());
        for (var configured : entries(settings, input)) {
            var mesh = entry(configured, input, settings);
            if (mesh.asset() != null)
                MeshLodSelection.requestStreamingLod(mesh.asset(), mesh, lodView, ownerCentre, camera, recentlyRendered);
        }
    }

    public static int lod(StaticMeshAsset asset, MeshRendererSettings.Entry entry, MeshRendererSettings settings,
                          MeshView meshView, EffectHandle.Input input, Vec3 camera,
                          ToIntFunction<MeshRendererSettings.IntegerBinding> integers, Supplier<NiagaraBounds> componentBounds) {
        var pose = input.pose();
        var ownerRotation = new Quaternionf(-pose.x(), -pose.z(), -pose.y(), pose.w());
        var baseBounds = asset.extendedBounds();
        Vec3 centre = baseBounds.origin().multiply(entry.scale());
        Vec3 extent = baseBounds.extent().multiply(entry.scale()).multiply(settings.boundsScale());
            int selectedLod = Math.max(MeshLodSelection.minimum(asset, meshView), integers.applyAsInt(entry.lodLevel()));
            if (entry.lodMode() == MeshRendererSettings.LodMode.LOD_BIAS)
                selectedLod = Math.max(0, MeshLodSelection.minimum(asset, meshView) + integers.applyAsInt(entry.lodBias()));
            else if (entry.lodMode() == MeshRendererSettings.LodMode.BY_COMPONENT_BOUNDS) {
                var bounds = componentBounds.get();
                Vec3 boundsCentre = new Vec3((bounds.minX() + bounds.maxX()) * .005,
                        (bounds.minZ() + bounds.maxZ()) * .005, (bounds.minY() + bounds.maxY()) * .005);
                float radius = (float)(new Vec3(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY(),
                        bounds.maxZ() - bounds.minZ()).length() * .5
                        * com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.maximumScale(pose));
                selectedLod = MeshLodSelection.component(asset, meshView,
                        pose.transformPosition(boundsCentre).distanceToSqr(camera) * 10000, radius, entry.lodDistanceFactor());
            } else if (entry.lodMode() == MeshRendererSettings.LodMode.COMPONENT_ORIGIN) {
                Vec3 worldCentre = pose.transformPosition(new Vec3(centre.x * .01, centre.z * .01, centre.y * .01));
                var rotation = new org.joml.Matrix3f().rotation(ownerRotation);
                double ex = Math.abs(rotation.m00() * extent.x * pose.scaleX()) + Math.abs(rotation.m10() * extent.y * pose.scaleZ()) + Math.abs(rotation.m20() * extent.z * pose.scaleY());
                double ey = Math.abs(rotation.m01() * extent.x * pose.scaleX()) + Math.abs(rotation.m11() * extent.y * pose.scaleZ()) + Math.abs(rotation.m21() * extent.z * pose.scaleY());
                double ez = Math.abs(rotation.m02() * extent.x * pose.scaleX()) + Math.abs(rotation.m12() * extent.y * pose.scaleZ()) + Math.abs(rotation.m22() * extent.z * pose.scaleY());
                float radius = (float)Math.sqrt(ex * ex + ey * ey + ez * ez);
                selectedLod = MeshLodSelection.component(asset, meshView, worldCentre.distanceToSqr(camera) * 10000,
                        radius, entry.lodDistanceFactor());
            }
        return selectedLod;
    }

    public static MaterialDefinition material(EffectDefinition effect, EmitterDefinition definition, StaticMeshAsset asset,
                                               MeshRendererSettings.Entry entry, StaticMeshGeometry.Section sourceSection) {
        return material(effect, definition, asset, entry, sourceSection, null);
    }

    public static MaterialDefinition material(EffectDefinition effect, EmitterDefinition definition, StaticMeshAsset asset,
                                               MeshRendererSettings.Entry entry, StaticMeshGeometry.Section sourceSection,
                                               @Nullable Input input) {
        return material(effect, definition.material(), java.util.Objects.requireNonNull(definition.meshSettings()),
                asset, entry, sourceSection, input);
    }

    public static MaterialDefinition material(EffectDefinition effect, String fallback, MeshRendererSettings settings,
            StaticMeshAsset asset, MeshRendererSettings.Entry entry, StaticMeshGeometry.Section sourceSection, @Nullable Input input) {
        int index = sourceSection.info() == null ? -1 : sourceSection.info().materialIndex();
        for (int slot = 0; sourceSection.info() == null && slot < asset.materials().size(); slot++) {
            var materialSlot = asset.materials().get(slot);
            if (materialSlot.importedName().equals(sourceSection.materialSlot()) || materialSlot.name().equals(sourceSection.materialSlot())) {
                index = slot; break;
            }
        }
        return materialAtSlot(effect, fallback, settings, asset, entry, index, input);
    }

    public static MaterialDefinition materialAtSlot(EffectDefinition effect, EmitterDefinition definition, StaticMeshAsset asset,
                                                     MeshRendererSettings.Entry entry, int index, @Nullable Input input) {
        return materialAtSlot(effect, definition.material(), java.util.Objects.requireNonNull(definition.meshSettings()),
                asset, entry, index, input);
    }

    private static MaterialDefinition materialAtSlot(EffectDefinition effect, String fallback, MeshRendererSettings settings,
            StaticMeshAsset asset, MeshRendererSettings.Entry entry, int index, @Nullable Input input) {
        var defaults = index >= 0 && index < entry.materials().size() ? entry.materials().get(index) : null;
        MaterialDefinition material = index >= 0 && index < asset.materials().size() ? asset.materials().get(index).material() : null;
        if (material == null && defaults != null && defaults.defaultMaterial() != null)
            material = requiredMaterial(effect, defaults.defaultMaterial());
        if (material == null) material = asset.fallbackMaterial();
        if (material == null) material = requiredMaterial(effect, fallback);
        var binding = index >= 0 && index < settings.overrideMaterials().size() ? settings.overrideMaterials().get(index) : defaults;
        if (binding != null) {
            var user = parameter(input, binding.userParameter());
            MaterialDefinition override = RendererParameter.material(user, binding.materialVariant());
            if (override != null) material = override;
            else if (binding.material() != null) material = requiredMaterial(effect, binding.material());
        }
        String replacement = settings.materialRemap().get(material.sourceObject());
        if (replacement != null) material = requiredMaterial(effect, replacement);
        return material;
    }

    private static MaterialDefinition requiredMaterial(EffectDefinition effect, String name) {
        return java.util.Objects.requireNonNull(effect.materials().get(name), "Missing mesh material " + name);
    }

    public static boolean hasStaticOverride(EffectHandle.Input input, String name) {
        return parameter(input, name) instanceof RendererParameter.Mesh mesh
                && drawable(mesh.value(), NiagaraPlatforms.snapshot().shaderPlatforms().featureLevel());
    }

    private static boolean drawable(@Nullable StaticMeshAsset mesh, String format) {
        return mesh != null && mesh.sampling().fallbackAvailable(format);
    }

    public static MaterialDefinition dynamicMaterial(EffectDefinition effect, EmitterDefinition definition,
                                                      NiagaraDynamicMeshRenderSource source, int section, EffectHandle.Input input) {
        var settings = java.util.Objects.requireNonNull(definition.meshSettings());
        String path = SourceNames.canonical(source.materialSources().get(section));
        if (path.equals("none")) path = "";
        var selected = RendererParameter.material(parameter(input, source.materialParameters().get(section)), settings.materialVariant());
        if (selected == null) {
            String name = settings.dynamicMaterials().get(path);
            if (name == null && !path.isEmpty()) throw new IllegalArgumentException("Missing compiled dynamic mesh material " + path);
            selected = requiredMaterial(effect, name == null ? definition.material() : name);
        }
        int index = source.geometry().frame().sections().get(section).materialIndex();
        if (index >= 0 && index < settings.overrideMaterials().size()) {
            var binding = settings.overrideMaterials().get(index);
            var user = RendererParameter.material(parameter(input, binding.userParameter()), binding.materialVariant());
            if (user != null) selected = user;
            else if (binding.material() != null) selected = requiredMaterial(effect, binding.material());
        }
        String replacement = settings.materialRemap().get(selected.sourceObject());
        return replacement == null ? selected : requiredMaterial(effect, replacement);
    }
}
