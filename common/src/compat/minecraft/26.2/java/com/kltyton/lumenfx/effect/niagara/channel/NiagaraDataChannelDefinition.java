package com.kltyton.lumenfx.effect.niagara.channel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Saved channel schema and the read or write interface that uses it. */
public record NiagaraDataChannelDefinition(@Nullable Channel channel, boolean writer, boolean readCurrent, boolean refresh,
                                            boolean onlyFinalSubtick, boolean publishGame, boolean publishCpu,
                                            boolean publishGpu, int allocationCount, boolean spawnGroups, boolean spawnOnce,
                                            boolean gpuSpawningReader, boolean autoLink,
                                            @Nullable AccessContextConfig accessContext) {
    public NiagaraDataChannelDefinition(@Nullable Channel channel, boolean writer, boolean readCurrent, boolean refresh,
                                        boolean onlyFinalSubtick, boolean publishGame, boolean publishCpu,
                                        boolean publishGpu, int allocationCount, boolean spawnGroups, boolean spawnOnce,
                                        boolean gpuSpawningReader) {
        this(channel, writer, readCurrent, refresh, onlyFinalSubtick, publishGame, publishCpu, publishGpu,
                allocationCount, spawnGroups, spawnOnce, gpuSpawningReader, !writer, null);
    }

    public record AccessContextConfig(boolean overrideLocation, Vec3 location, boolean overrideSystem,
                                      List<Identifier> systems, String selectedSystem, boolean forceAttach,
                                      boolean overrideCellSize, @Nullable Vec3 cellSizeOverride,
                                      boolean overrideBoundsPadding, @Nullable Vec3 boundsPaddingOverride,
                                      String gameplayTag) {
        public AccessContextConfig(boolean overrideLocation, Vec3 location, boolean overrideSystem,
                                   List<Identifier> systems, boolean forceAttach, boolean overrideCellSize,
                                   @Nullable Vec3 cellSizeOverride, boolean overrideBoundsPadding,
                                   @Nullable Vec3 boundsPaddingOverride, String gameplayTag) {
            this(overrideLocation, location, overrideSystem, systems, "", forceAttach, overrideCellSize,
                    cellSizeOverride, overrideBoundsPadding, boundsPaddingOverride, gameplayTag);
        }

        public AccessContextConfig {
            systems = systems == null ? List.of() : List.copyOf(systems);
            selectedSystem = selectedSystem == null ? "" : SourceNames.canonical(selectedSystem);
            gameplayTag = gameplayTag == null ? "" : gameplayTag;
        }
    }
    public enum Register { FLOAT, INT, BOOL, HALF }

    public record Variable(String name, String type, List<Register> registers) {
        public Variable {
            name = SourceNames.canonical(name);
            type = SourceNames.canonical(type);
            registers = List.copyOf(registers);
            if (name.isEmpty() || type.isEmpty() || registers.isEmpty())
                throw new IllegalArgumentException("Invalid Niagara data channel variable");
        }
        public int width() { return registers.size(); }
    }

    public record Islands(boolean aligned, Vec3 initial, Vec3 maximum, Vec3 padding, int poolSize, List<Identifier> systems) {
        public Islands {
            systems = List.copyOf(systems);
            if (!positive(initial) || !positive(maximum) || !nonNegative(padding) || poolSize < 0)
                throw new IllegalArgumentException("Invalid Niagara data channel island bounds or pool size");
        }
        private static boolean positive(Vec3 value) {
            return nonNegative(value) && value.x > 0 && value.y > 0 && value.z > 0;
        }
        private static boolean nonNegative(Vec3 value) {
            return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
                    && value.x >= 0 && value.y >= 0 && value.z >= 0;
        }
    }

    public record AttachmentSettings(double speedThreshold, List<String> gameplayTags, List<String> componentTypes) {
        public static final AttachmentSettings NONE = new AttachmentSettings(-1.0, List.of(), List.of());

        public AttachmentSettings {
            gameplayTags = List.copyOf(gameplayTags);
            componentTypes = List.copyOf(componentTypes);
            if (!Double.isFinite(speedThreshold))
                throw new IllegalArgumentException("Invalid Niagara GameplayBurst speed threshold");
        }
    }

    public record GameplayBurst(Vec3 cellSize, Vec3 boundsPadding, List<Identifier> systems,
                                String defaultSelectedSystem, AttachmentSettings attachment) {
        public static final Vec3 DEFAULT_CELL_SIZE = new Vec3(2500.0, 2500.0, 2500.0);
        public static final Vec3 DEFAULT_BOUNDS_PADDING = new Vec3(250.0, 250.0, 250.0);

        public GameplayBurst(Vec3 cellSize, Vec3 boundsPadding, List<Identifier> systems,
                             AttachmentSettings attachment) {
            this(cellSize, boundsPadding, systems, "", attachment);
        }

        public GameplayBurst {
            systems = List.copyOf(systems);
            defaultSelectedSystem = defaultSelectedSystem == null ? "" : SourceNames.canonical(defaultSelectedSystem);
            attachment = attachment == null ? AttachmentSettings.NONE : attachment;
            if (!Double.isFinite(cellSize.x) || !Double.isFinite(cellSize.y) || !Double.isFinite(cellSize.z)
                    || cellSize.x <= 0 || cellSize.y <= 0 || cellSize.z <= 0)
                throw new IllegalArgumentException("Invalid Niagara GameplayBurst cell size");
            if (!Double.isFinite(boundsPadding.x) || !Double.isFinite(boundsPadding.y) || !Double.isFinite(boundsPadding.z)
                    || boundsPadding.x < 0 || boundsPadding.y < 0 || boundsPadding.z < 0)
                throw new IllegalArgumentException("Invalid Niagara GameplayBurst bounds padding");
        }
    }

    public record Address(Channel channel, long island) { }

    public record Channel(String path, List<Variable> variables, boolean keepPrevious, boolean enforceTickOrder,
                          int finalWriteGroup, @Nullable Islands islands, @Nullable GameplayBurst gameplayBurst) {
        public Channel(String path, List<Variable> variables, boolean keepPrevious, boolean enforceTickOrder,
                       int finalWriteGroup, @Nullable Islands islands) {
            this(path, variables, keepPrevious, enforceTickOrder, finalWriteGroup, islands, null);
        }

        public Channel {
            if (finalWriteGroup < 0 || finalWriteGroup > ComponentTickGroup.TG_MAX.ordinal())
                throw new IllegalArgumentException("Invalid Niagara data channel final tick group: " + finalWriteGroup);
            path = SourceNames.canonical(path);
            variables = List.copyOf(variables);
            var names = new HashSet<String>();
            for (var variable : variables) if (!names.add(variable.name()))
                throw new IllegalArgumentException("Duplicate Niagara data channel variable " + variable.name() + " in " + path);
        }
        public ComponentTickGroup finalWriteTickGroup() { return ComponentTickGroup.values()[finalWriteGroup]; }

        public int width() {
            int width = 0;
            for (var variable : variables) width = Math.addExact(width, variable.width());
            return width;
        }
        public int offset(Variable requested) {
            int offset = 0;
            for (var variable : variables) {
                if (variable.equals(requested)) return offset;
                offset += variable.width();
            }
            return -1;
        }
    }

    public static NiagaraDataChannelDefinition read(JsonObject data) {
        Channel schema = null;
        if (data.has("channel")) {
            var channel = data.getAsJsonObject("channel");
            var group = channel.getAsJsonPrimitive("final_write_group");
            int index = group.isNumber() ? group.getAsInt()
                    : ComponentTickGroup.valueOf(group.getAsString().replace("ETickingGroup::", "")).ordinal();
            Islands islands = null;
            if (channel.has("islands")) {
                var source = channel.getAsJsonObject("islands");
                boolean aligned = switch (source.get("mode").getAsString()) {
                    case "AlignedStatic" -> true;
                    case "Dynamic" -> false;
                    default -> throw new IllegalArgumentException("Unknown Niagara data channel island mode");
                };
                var systems = new ArrayList<Identifier>();
                for (var system : source.getAsJsonArray("systems")) systems.add(Identifier.parse(system.getAsString()));
                islands = new Islands(aligned, vector(source, "initial_extents"), vector(source, "maximum_extents"),
                        vector(source, "element_extents"), source.get("pool_size").getAsInt(), systems);
            }
            GameplayBurst gameplayBurst = null;
            if (channel.has("gameplay_burst")) {
                var source = channel.getAsJsonObject("gameplay_burst");
                var systems = new ArrayList<Identifier>();
                if (source.has("systems")) {
                    for (var system : source.getAsJsonArray("systems")) systems.add(Identifier.parse(system.getAsString()));
                }
                var attachObj = source.has("attachment") ? source.getAsJsonObject("attachment") : null;
                var tags = new ArrayList<String>();
                if (attachObj != null && attachObj.has("gameplay_tags")) {
                    for (var t : attachObj.getAsJsonArray("gameplay_tags")) tags.add(t.getAsString());
                }
                var compTypes = new ArrayList<String>();
                if (attachObj != null && attachObj.has("component_types")) {
                    for (var c : attachObj.getAsJsonArray("component_types")) compTypes.add(c.getAsString());
                }
                var attach = new AttachmentSettings(
                        attachObj != null && attachObj.has("speed_threshold") ? attachObj.get("speed_threshold").getAsDouble() : -1.0,
                        tags, compTypes);
                Vec3 cellSize = source.has("cell_size") ? vector(source, "cell_size") : GameplayBurst.DEFAULT_CELL_SIZE;
                Vec3 boundsPadding = source.has("bounds_padding") ? vector(source, "bounds_padding") : GameplayBurst.DEFAULT_BOUNDS_PADDING;
                String defaultSelected = source.has("default_selected_system")
                        ? source.get("default_selected_system").getAsString() : "";
                gameplayBurst = new GameplayBurst(cellSize, boundsPadding, systems, defaultSelected, attach);
            }
            schema = new Channel(channel.get("path").getAsString(), variables(channel.getAsJsonArray("fields")),
                    channel.get("keep_previous").getAsBoolean(), channel.get("enforce_tick_order").getAsBoolean(),
                    index, islands, gameplayBurst);
        }
        int allocation = Math.toIntExact(data.get("allocation_count").getAsLong());
        if (allocation < 0) throw new IllegalArgumentException("Negative Niagara data channel allocation");
        boolean writer = data.get("writer").getAsBoolean();
        boolean autoLink = data.has("auto_link") ? data.get("auto_link").getAsBoolean() : !writer;
        AccessContextConfig accessContext = null;
        if (data.has("access_context") && data.get("access_context").isJsonObject()) {
            var ctxObj = data.getAsJsonObject("access_context");
            var systems = new ArrayList<Identifier>();
            if (ctxObj.has("systems")) {
                for (var s : ctxObj.getAsJsonArray("systems")) systems.add(Identifier.parse(s.getAsString()));
            }
            boolean overrideCell = ctxObj.has("override_cell_size") && ctxObj.get("override_cell_size").getAsBoolean();
            boolean overridePadding = ctxObj.has("override_bounds_padding") && ctxObj.get("override_bounds_padding").getAsBoolean();
            accessContext = new AccessContextConfig(
                    ctxObj.has("override_location") && ctxObj.get("override_location").getAsBoolean(),
                    ctxObj.has("location") ? vector(ctxObj, "location") : Vec3.ZERO,
                    ctxObj.has("override_system") && ctxObj.get("override_system").getAsBoolean(),
                    systems,
                    ctxObj.has("selected_system") ? ctxObj.get("selected_system").getAsString() : "",
                    ctxObj.has("force_attach") && ctxObj.get("force_attach").getAsBoolean(),
                    overrideCell,
                    overrideCell && ctxObj.has("cell_size_override") ? vector(ctxObj, "cell_size_override") : null,
                    overridePadding,
                    overridePadding && ctxObj.has("bounds_padding_override") ? vector(ctxObj, "bounds_padding_override") : null,
                    ctxObj.has("gameplay_tag") ? ctxObj.get("gameplay_tag").getAsString() : "");
        }
        return new NiagaraDataChannelDefinition(schema, writer, data.get("read_current").getAsBoolean(),
                data.get("refresh").getAsBoolean(), data.get("only_final_subtick").getAsBoolean(),
                data.get("publish_game").getAsBoolean(), data.get("publish_cpu").getAsBoolean(), data.get("publish_gpu").getAsBoolean(),
                allocation, data.get("spawn_groups").getAsBoolean(), data.get("spawn_once").getAsBoolean(),
                data.get("gpu_spawning_reader").getAsBoolean(), autoLink, accessContext);
    }

    private static Vec3 vector(JsonObject value, String name) {
        var source = value.getAsJsonArray(name);
        if (source.size() != 3) throw new IllegalArgumentException("Invalid Niagara island vector " + name);
        return new Vec3(source.get(0).getAsDouble(), source.get(1).getAsDouble(), source.get(2).getAsDouble());
    }

    public static List<Variable> variables(JsonArray source) {
        var values = new ArrayList<Variable>();
        for (var item : source) {
            var variable = item.getAsJsonObject();
            var fields = new ArrayList<Register>();
            for (var type : variable.getAsJsonArray("registers")) fields.add(Register.valueOf(type.getAsString()));
            values.add(new Variable(variable.get("name").getAsString(), variable.get("type").getAsString(), fields));
        }
        return List.copyOf(values);
    }
}
