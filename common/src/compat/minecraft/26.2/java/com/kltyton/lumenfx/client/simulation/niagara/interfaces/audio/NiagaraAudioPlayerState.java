package com.kltyton.lumenfx.client.simulation.niagara.interfaces.audio;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Overrides;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Playback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Voice;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.client.audio.scene.NiagaraAudioPose;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioPlayerDefinition;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** A physical Audio Player object queues simulation commands for client-thread playback. */
public final class NiagaraAudioPlayerState implements AutoCloseable {
    private record Play(Vec3 position, Vec3 rotation, float volume, float pitch, float start, float fade, int particle) { }
    private record Initial(int particle, String name, Object value) { }
    private record Action(int handle, @Nullable Play play, @Nullable String update, @Nullable Object value, float fade) { }
    private record Playing(Voice voice, boolean looping, Vec3 attachedPosition, Quaterniond attachedRotation) { }
    private record Frame(Vec3 origin, Matrix4d owner, Matrix4d inverseOwner, Quaterniond rotation, NiagaraAudioBackend backend,
                         @Nullable Overrides overrides, @Nullable Object ownerIdentity, boolean active) { }

    private final NiagaraAudioPlayerDefinition definition;
    private final ConcurrentLinkedQueue<Play> oneShots = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Initial> initial = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Action> actions = new ConcurrentLinkedQueue<>();
    private final Map<String, Object> globalInitial = new ConcurrentHashMap<>();
    private final AtomicInteger handles = new AtomicInteger();
    private final Map<Integer, Playing> playing = new HashMap<>();
    private volatile @Nullable Frame frame;
    private @Nullable Frame appliedFrame;

    public NiagaraAudioPlayerState(NiagaraAudioPlayerDefinition definition) { this.definition = definition; }
    public NiagaraAudioPlayerDefinition definition() { return definition; }

    public void capture(Pose pose, Vec3 origin, NiagaraAudioBackend backend, @Nullable Overrides settings,
                        @Nullable Object ownerIdentity, boolean active) {
        var owner = NiagaraComponentMatrices.source(pose, 0, 0, 0, true, new Matrix4d());
        var inverse = NiagaraComponentMatrices.inverse(owner, new Matrix4d());
        var rotation = new Quaterniond(-pose.x(), -pose.z(), -pose.y(), pose.w());
        frame = new Frame(origin, owner, inverse, rotation, backend, settings, ownerIdentity, active);
    }

    public int execute(String operation, int[] input) {
        var tick = frame;
        if (tick == null) return 0;
        return switch (operation) {
            case "PlayAudioAtLocation" -> oneShot(tick, input) ? -1 : 0;
            case "PlayPersistentAudio" -> persistent(tick, input);
            case "SetInitialBooleanParameter", "SetInitialIntegerParameter", "SetInitialFloatParameter" -> {
                String parameter = parameterName(input[2]);
                if (parameter != null) {
                    Object value = parameterValue(operation, input[3]);
                    if (input[0] != 0) globalInitial.put(parameter, value);
                    else if (input[1] >= 0) initial.add(new Initial(input[1], parameter, value));
                }
                yield 0;
            }
            case "SetBooleanParameter", "SetIntegerParameter", "SetFloatParameter" -> {
                String parameter = parameterName(input[1]);
                if (input[0] > 0 && parameter != null)
                    actions.add(new Action(input[0], null, "parameter", Map.entry(parameter, parameterValue(operation, input[2])), 0));
                yield 0;
            }
            case "UpdateAudioVolume", "UpdateAudioPitch", "UpdateAudioLocation", "UpdateAudioRotation", "SetPaused" -> {
                if (input[0] > 0) {
                    Object value = switch (operation) {
                        case "UpdateAudioLocation" -> vector(input, 1).add(tick.origin());
                        case "UpdateAudioRotation" -> vector(input, 1);
                        case "SetPaused" -> input[1] != 0;
                        default -> floating(input[1]);
                    };
                    actions.add(new Action(input[0], null, operation, value, 0));
                }
                yield 0;
            }
            default -> throw new IllegalArgumentException("Unknown Niagara Audio Player function " + operation);
        };
    }

    private boolean oneShot(Frame tick, int[] input) {
        var settings = definition.settings();
        if (input[0] == 0 || settings.sound() == null || settings.looping() && !definition.loopingOneShots()) return false;
        int particle = input.length > 10 ? input[10] : -1;
        return oneShots.add(new Play(vector(input, 1).add(tick.origin()), vector(input, 4), floating(input[7]),
                floating(input[8]), floating(input[9]), 0, particle));
    }

    private int persistent(Frame tick, int[] input) {
        int handle = input[1];
        if (input[0] == 0) {
            if (handle > 0) actions.add(new Action(handle, null, "stop", null, floating(input[12])));
            return 0;
        }
        if (handle > 0) {
            actions.add(new Action(handle, null, "keep", null, 0));
            return handle;
        }
        handle = handles.incrementAndGet();
        var request = new Play(vector(input, 2).add(tick.origin()), vector(input, 5), floating(input[8]), floating(input[9]),
                floating(input[10]), floating(input[11]), -1);
        actions.add(new Action(handle, request, null, null, 0));
        return handle;
    }

    public void postSimulate() {
        var tick = frame;
        if (tick == null || !tick.active()) { oneShots.clear(); initial.clear(); actions.clear(); return; }
        var parameters = new HashMap<Integer, Map<String, Object>>();
        if (!oneShots.isEmpty()) {
            for (Initial next; (next = initial.poll()) != null;)
                parameters.computeIfAbsent(next.particle(), ignored -> new HashMap<>()).put(next.name(), next.value());
        } else initial.clear();
        int played = 0;
        for (Play request; (request = oneShots.poll()) != null;) {
            var values = new HashMap<>(globalInitial);
            values.putAll(parameters.getOrDefault(request.particle(), Map.of()));
            play(tick, request, false, values);
            if (++played == definition.maximumOneShots()) { oneShots.clear(); break; }
        }
        var prior = appliedFrame;
        boolean moved = prior == null || !tick.owner().equals(prior.owner());
        boolean turned = prior == null || !tick.rotation().equals(prior.rotation());
        for (var voice : playing.values()) {
            if (moved) voice.voice().position(transform(tick.owner(), voice.attachedPosition()));
            if (turned) voice.voice().rotation(NiagaraAudioPose.rotator(new Quaterniond(tick.rotation()).mul(voice.attachedRotation())));
        }
        appliedFrame = tick;
        var kept = new HashSet<Integer>();
        for (Action action; (action = actions.poll()) != null;) {
            int handle = action.handle();
            kept.add(handle);
            var request = action.play();
            if (request != null) {
                Voice voice = play(tick, request, true, Map.of());
                if (voice != null) playing.put(handle, new Playing(voice, definition.settings().looping(),
                        transform(tick.inverseOwner(), request.position()), attachedRotation(tick, request.rotation())));
                continue;
            }
            var current = playing.get(handle);
            if (current != null) update(tick, handle, current, action);
        }
        var iterator = playing.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (kept.contains(entry.getKey())) continue;
            entry.getValue().voice().stop(0);
            iterator.remove();
        }
    }

    private @Nullable Voice play(Frame tick, Play request, boolean persistent, Map<String, Object> parameters) {
        var settings = definition.settings();
        var sound = settings.sound();
        if (sound == null) return null;
        var override = persistent ? tick.overrides() : null;
        var concurrency = override != null && override.overrideConcurrency() ? override.concurrency() : settings.concurrency();
        return tick.backend().play(new Playback(sound, settings.looping(), request.position(), request.rotation(),
                request.volume() * settings.volume(), request.pitch() * settings.pitch(), request.start(), request.fade(), parameters,
                override != null && override.overrideAttenuation() ? override.attenuation() : settings.attenuation(),
                concurrency == null ? settings.assetConcurrency() : List.of(concurrency), settings.cue(),
                tick.ownerIdentity(), settings.priority(), null, settings.routing()));
    }

    private void update(Frame tick, int handle, Playing playingVoice, Action action) {
        var voice = playingVoice.voice();
        switch (action.update()) {
            case "keep" -> { }
            case "stop" -> { voice.stop(action.fade()); playing.remove(handle); }
            case "UpdateAudioVolume" -> voice.volume((float)action.value() * definition.settings().volume());
            case "UpdateAudioPitch" -> voice.pitch((float)action.value() * definition.settings().pitch());
            case "UpdateAudioRotation" -> {
                var rotation = (Vec3)action.value();
                voice.rotation(rotation);
                playing.put(handle, new Playing(voice, playingVoice.looping(), playingVoice.attachedPosition(), attachedRotation(tick, rotation)));
            }
            case "SetPaused" -> voice.paused((boolean)action.value());
            case "UpdateAudioLocation" -> {
                var position = (Vec3)action.value();
                voice.position(position);
                playing.put(handle, new Playing(voice, playingVoice.looping(), transform(tick.inverseOwner(), position), playingVoice.attachedRotation()));
            }
            case "parameter" -> {
                var parameter = (Map.Entry<?, ?>)action.value();
                voice.parameter((String)parameter.getKey(), parameter.getValue());
            }
            default -> throw new IllegalStateException("Unknown Niagara audio voice action " + action.update());
        }
    }

    @Override public void close() {
        // DestroyPerInstanceData stops every mapped persistent component. The
        // stop-with-attachment option affects component ownership, not DI teardown.
        for (var voice : playing.values()) voice.voice().stop(0);
        playing.clear();
        oneShots.clear();
        actions.clear();
        initial.clear();
        globalInitial.clear();
        frame = null;
        appliedFrame = null;
    }

    private @Nullable String parameterName(int index) { return index < 0 || index >= definition.parameters().size() ? null : definition.parameters().get(index); }
    private static Object parameterValue(String name, int value) {
        if (name.contains("Boolean")) return value != 0;
        if (name.contains("Float")) return floating(value);
        return value;
    }
    private static Vec3 vector(int[] values, int offset) {
        return new Vec3(floating(values[offset]), floating(values[offset + 1]), floating(values[offset + 2]));
    }
    private static float floating(int value) { return Float.intBitsToFloat(value); }
    private static Vec3 transform(Matrix4d matrix, Vec3 value) {
        var result = matrix.transformPosition(new Vector3d(value.x, value.y, value.z));
        return new Vec3(result.x, result.y, result.z);
    }

    private static Quaterniond attachedRotation(Frame owner, Vec3 rotation) {
        double pitch = Math.toRadians(rotation.x) * .5, yaw = Math.toRadians(rotation.y) * .5, roll = Math.toRadians(rotation.z) * .5;
        double cp = Math.cos(pitch), sp = Math.sin(pitch), cy = Math.cos(yaw), sy = Math.sin(yaw), cr = Math.cos(roll), sr = Math.sin(roll);
        var world = new Quaterniond(cr * sp * sy - sr * cp * cy, -cr * sp * cy - sr * cp * sy,
                cr * cp * sy - sr * sp * cy, cr * cp * cy + sr * sp * sy);
        return new Quaterniond(owner.rotation()).invert().mul(world);
    }

}
