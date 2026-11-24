package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Game-side access to Niagara data channels declared by loaded effect assets. */
public final class NiagaraDataChannels {
    private NiagaraDataChannels() { }

    /** Field components are in source order. Position components are absolute source centimeters. */
    public record Row(Map<String, List<Number>> fields) {
        public Row {
            var values = new LinkedHashMap<String, List<Number>>();
            fields.forEach((name, components) -> {
                if (values.putIfAbsent(SourceNames.canonical(name), List.copyOf(components)) != null)
                    throw new IllegalArgumentException("Duplicate Niagara data channel field: " + name);
            });
            fields = Map.copyOf(values);
        }
    }
    public record Visibility(boolean game, boolean cpu, boolean gpu) { }

    /** Explicit access context matching UE5 GameplayBurst / Map NDC routing parameters (location in Minecraft blocks). */
    public record AccessContext(@Nullable Vec3 location, @Nullable Object ownerKey, @Nullable Vec3 ownerLocation,
                                @Nullable NiagaraBounds ownerBounds, double ownerSpeed, double attachParentSpeed,
                                List<String> ownerTags, @Nullable String ownerComponentType,
                                List<String> ownerComponentSuperclasses, boolean forceAttach,
                                @Nullable Vec3 cellSizeOverride, @Nullable Vec3 boundsPaddingOverride,
                                String gameplayTag, @Nullable Identifier systemToSpawn,
                                List<Identifier> systemsToSpawn,
                                boolean overrideSystemToSpawn, @Nullable String selectedSystemKey,
                                @Nullable Supplier<EffectHandle.Pose> ownerPose,
                                @Nullable BooleanSupplier ownerValid) {
        public AccessContext(@Nullable Vec3 location, @Nullable Object ownerKey, @Nullable Vec3 ownerLocation,
                             @Nullable NiagaraBounds ownerBounds, double ownerSpeed, List<String> ownerTags,
                             @Nullable String ownerComponentType, boolean forceAttach,
                             @Nullable Vec3 cellSizeOverride, @Nullable Vec3 boundsPaddingOverride,
                             String gameplayTag, @Nullable Identifier systemToSpawn,
                             List<Identifier> systemsToSpawn,
                             boolean overrideSystemToSpawn, @Nullable String selectedSystemKey,
                             @Nullable Supplier<EffectHandle.Pose> ownerPose,
                             @Nullable BooleanSupplier ownerValid) {
            this(location, ownerKey, ownerLocation, ownerBounds, ownerSpeed, 0.0, ownerTags, ownerComponentType,
                    List.of(), forceAttach, cellSizeOverride, boundsPaddingOverride, gameplayTag, systemToSpawn,
                    systemsToSpawn, overrideSystemToSpawn, selectedSystemKey, ownerPose, ownerValid);
        }

        public AccessContext(@Nullable Vec3 location, @Nullable Object ownerKey, @Nullable Vec3 ownerLocation,
                             @Nullable NiagaraBounds ownerBounds, double ownerSpeed, List<String> ownerTags,
                             @Nullable String ownerComponentType, boolean forceAttach,
                             @Nullable Vec3 cellSizeOverride, @Nullable Vec3 boundsPaddingOverride,
                             String gameplayTag, @Nullable Identifier systemToSpawn,
                             List<Identifier> systemsToSpawn,
                             @Nullable Supplier<EffectHandle.Pose> ownerPose,
                             @Nullable BooleanSupplier ownerValid) {
            this(location, ownerKey, ownerLocation, ownerBounds, ownerSpeed, ownerTags, ownerComponentType,
                    forceAttach, cellSizeOverride, boundsPaddingOverride, gameplayTag, systemToSpawn,
                    systemsToSpawn,
                    systemToSpawn != null || (systemsToSpawn != null && !systemsToSpawn.isEmpty()),
                    "", ownerPose, ownerValid);
        }

        public AccessContext(@Nullable Vec3 location, @Nullable Object ownerKey, @Nullable Vec3 ownerLocation,
                             @Nullable NiagaraBounds ownerBounds, double ownerSpeed, List<String> ownerTags,
                             @Nullable String ownerComponentType, boolean forceAttach,
                             @Nullable Vec3 cellSizeOverride, @Nullable Vec3 boundsPaddingOverride,
                             String gameplayTag, @Nullable Identifier systemToSpawn) {
            this(location, ownerKey, ownerLocation, ownerBounds, ownerSpeed, ownerTags, ownerComponentType,
                    forceAttach, cellSizeOverride, boundsPaddingOverride, gameplayTag, systemToSpawn,
                    systemToSpawn == null ? List.of() : List.of(systemToSpawn), null, null);
        }

        public AccessContext {
            ownerTags = ownerTags == null ? List.of() : List.copyOf(ownerTags);
            ownerComponentSuperclasses = ownerComponentSuperclasses == null ? List.of() : List.copyOf(ownerComponentSuperclasses);
            gameplayTag = gameplayTag == null ? "" : gameplayTag;
            systemsToSpawn = systemsToSpawn == null ? List.of() : List.copyOf(systemsToSpawn);
            selectedSystemKey = selectedSystemKey == null ? "" : SourceNames.canonical(selectedSystemKey);
            if (!Double.isFinite(ownerSpeed) || ownerSpeed < 0 || !Double.isFinite(attachParentSpeed) || attachParentSpeed < 0)
                throw new IllegalArgumentException("Invalid Niagara channel owner speed");
        }

        public AccessContext withLocation(Vec3 resolvedLocation) {
            return new AccessContext(resolvedLocation, ownerKey, ownerLocation, ownerBounds, ownerSpeed, attachParentSpeed,
                    ownerTags, ownerComponentType, ownerComponentSuperclasses, forceAttach, cellSizeOverride,
                    boundsPaddingOverride, gameplayTag, systemToSpawn, systemsToSpawn, overrideSystemToSpawn,
                    selectedSystemKey, ownerPose, ownerValid);
        }

        public AccessContext withSystemOverride(boolean overrideSystem, @Nullable Identifier system,
                                                List<Identifier> systems, @Nullable String selectedKey) {
            return new AccessContext(location, ownerKey, ownerLocation, ownerBounds, ownerSpeed, attachParentSpeed,
                    ownerTags, ownerComponentType, ownerComponentSuperclasses, forceAttach, cellSizeOverride,
                    boundsPaddingOverride, gameplayTag, system, systems, overrideSystem, selectedKey, ownerPose, ownerValid);
        }

        public static AccessContext at(@Nullable Vec3 location) {
            return new AccessContext(location, null, null, null, 0.0, List.of(), null, false, null, null, "", null);
        }

        public static AccessContext withTag(Vec3 location, String gameplayTag) {
            return new AccessContext(location, null, null, null, 0.0, List.of(), null, false, null, null, gameplayTag, null);
        }

        public static AccessContext attached(EffectHandle owner) {
            Objects.requireNonNull(owner);
            return fromOwner(owner, owner.input().pose().position(), true, null, null, "", null, List.of(), false, "");
        }

        private static double velocitySpeed(com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.@Nullable Velocity vel) {
            return vel == null ? 0.0 : Math.sqrt(vel.x() * vel.x() + vel.y() * vel.y() + vel.z() * vel.z());
        }

        /** Builds an AccessContext extracting live component + attach-parent speeds, bounds, tags, and exact class identity from owner. */
        public static AccessContext fromOwner(@Nullable EffectHandle owner, Vec3 resolvedLocation, boolean forceAttach,
                                              @Nullable Vec3 cellSizeOverride, @Nullable Vec3 boundsPaddingOverride,
                                              String gameplayTag, @Nullable Identifier systemToSpawn,
                                              List<Identifier> systemsToSpawn, boolean overrideSystemToSpawn,
                                              @Nullable String selectedSystemKey) {
            if (owner == null) {
                return new AccessContext(resolvedLocation, null, resolvedLocation, null, 0.0, 0.0, List.of(), null,
                        List.of(), forceAttach, cellSizeOverride, boundsPaddingOverride, gameplayTag, systemToSpawn,
                        systemsToSpawn, overrideSystemToSpawn, selectedSystemKey, null, null);
            }
            var attached = owner.actorComponentInputs().attached();
            double physicsSpeed = velocitySpeed(attached != null ? attached.physicsVelocity() : null);
            double inputOwnerSpeed = velocitySpeed(owner.inputWithoutNiagaraAttachment().ownerVelocity());
            double attachedParentSpeed = velocitySpeed(attached != null ? attached.ownerVelocity() : null);
            double primarySpeed = attached != null && attached.physicsVelocity() != null ? physicsSpeed : Math.max(physicsSpeed, inputOwnerSpeed);
            double attachParentSpeed = Math.max(inputOwnerSpeed, attachedParentSpeed);
            var bounds = owner.niagaraOwnerBounds();
            if (bounds == null && attached != null) bounds = attached.bounds();
            if (bounds == null) bounds = owner.niagaraSystemFixedBounds();
            List<String> tags = !owner.niagaraOwnerGameplayTags().isEmpty() ? owner.niagaraOwnerGameplayTags()
                    : attached != null ? attached.gameplayTags() : List.of();
            String compType = owner.niagaraOwnerComponentType() != null ? owner.niagaraOwnerComponentType()
                    : attached != null ? attached.componentType() : null;
            List<String> superclasses = !owner.niagaraOwnerComponentSuperclasses().isEmpty()
                    ? owner.niagaraOwnerComponentSuperclasses()
                    : attached != null ? attached.componentSuperclasses() : List.of();
            return new AccessContext(resolvedLocation, owner, owner.input().pose().position(), bounds, primarySpeed,
                    attachParentSpeed, tags, compType, superclasses, forceAttach, cellSizeOverride, boundsPaddingOverride,
                    gameplayTag, systemToSpawn, systemsToSpawn, overrideSystemToSpawn, selectedSystemKey,
                    () -> owner.input().pose(), owner::isAlive);
        }

        public static AccessContext attached(Object ownerKey, Vec3 ownerLocation, @Nullable NiagaraBounds ownerBounds,
                                             boolean forceAttach, double ownerSpeed, List<String> ownerTags,
                                             @Nullable String ownerComponentType, String gameplayTag,
                                             @Nullable Identifier systemToSpawn) {
            Supplier<EffectHandle.Pose> livePose = ownerKey instanceof EffectHandle h ? () -> h.input().pose() : null;
            BooleanSupplier liveValid = ownerKey instanceof EffectHandle h ? h::isAlive : null;
            return new AccessContext(ownerLocation, ownerKey, ownerLocation, ownerBounds, ownerSpeed, ownerTags,
                    ownerComponentType, forceAttach, null, null, gameplayTag, systemToSpawn,
                    systemToSpawn == null ? List.of() : List.of(systemToSpawn), livePose, liveValid);
        }
    }

    public static Channel definition(String sourceAssetPath) { return VfxEngine.INSTANCE.dataChannel(sourceAssetPath); }

    public static void publish(ClientLevel world, String sourceAssetPath, List<Row> rows, Visibility visibility) {
        publish(world, sourceAssetPath, (Vec3) null, rows, visibility);
    }

    /** The location used to choose an island is expressed in Minecraft blocks. */
    public static void publish(ClientLevel world, String sourceAssetPath, @Nullable Vec3 location, List<Row> rows, Visibility visibility) {
        publish(world, sourceAssetPath, AccessContext.at(location), rows, visibility);
    }

    public static void publish(ClientLevel world, String sourceAssetPath, AccessContext context, List<Row> rows, Visibility visibility) {
        requireWorld(world);
        Objects.requireNonNull(context);
        if (!NiagaraPlatforms.snapshot().dataChannelsEnabled()) return;
        var channel = definition(sourceAssetPath);
        NiagaraDataChannelWorlds.publishGame(world, channel, resolveContext(channel, context), List.copyOf(rows), visibility);
    }

    public static List<Row> read(ClientLevel world, String sourceAssetPath, boolean previousFrame) {
        return read(world, sourceAssetPath, (Vec3) null, previousFrame);
    }

    public static List<Row> read(ClientLevel world, String sourceAssetPath, @Nullable Vec3 location, boolean previousFrame) {
        return read(world, sourceAssetPath, AccessContext.at(location), previousFrame);
    }

    public static List<Row> read(ClientLevel world, String sourceAssetPath, AccessContext context, boolean previousFrame) {
        requireWorld(world);
        Objects.requireNonNull(context);
        if (!NiagaraPlatforms.snapshot().dataChannelsEnabled()) return List.of();
        var channel = definition(sourceAssetPath);
        return NiagaraDataChannelWorlds.readGame(world, channel, resolveContext(channel, context), previousFrame);
    }

    private static AccessContext resolveContext(Channel channel, AccessContext context) {
        Vec3 loc = context.location() != null ? context.location() : context.ownerLocation();
        if (loc == null) {
            if (channel.islands() != null || channel.gameplayBurst() != null)
                throw new IllegalArgumentException("A Niagara spatial or GameplayBurst data channel requires a search location or owner");
            return AccessContext.at(Vec3.ZERO);
        }
        if (!Double.isFinite(loc.x) || !Double.isFinite(loc.y) || !Double.isFinite(loc.z))
            throw new IllegalArgumentException("Invalid Niagara channel search location");
        return context.location() != null ? context : context.withLocation(loc);
    }

    private static void requireWorld(ClientLevel world) {
        Objects.requireNonNull(world);
        var client = Minecraft.getInstance();
        if (!client.isSameThread() || client.level != world)
            throw new IllegalStateException("Niagara data channel game access requires the current client world thread");
    }
}
