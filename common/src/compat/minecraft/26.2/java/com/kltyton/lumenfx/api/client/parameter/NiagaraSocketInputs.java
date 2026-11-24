package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.component.ComponentTickWindow;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Host-published socket-bearing objects and the effect's attachment chain, nearest parent first.
 * Actor keys are source soft-object paths or the saved GUID strings of legacy actors. */
public record NiagaraSocketInputs(Map<String, Source> parameters, Map<String, Source> actors, List<Source> attachments) {
    public static final NiagaraSocketInputs EMPTY = new NiagaraSocketInputs(Map.of(), Map.of(), List.of());

    public NiagaraSocketInputs {
        parameters = named(parameters);
        actors = named(actors);
        attachments = List.copyOf(attachments);
    }

    /** Socket transforms are relative to the component, in source axes and centimeters. */
    public record Socket(String name, Transform local) {
        public Socket { Objects.requireNonNull(name); Objects.requireNonNull(local); }
    }

    /** Identity stays stable as transforms change. Bounds are optional absolute source-axis world centimeters. */
    public record Source(String identity, @Nullable Transform component, List<Socket> sockets, List<String> classes,
                         List<String> tags, @Nullable NiagaraBounds worldBounds, ComponentTickWindow tickWindow) {
        public Source(String identity, @Nullable Transform component, List<Socket> sockets, List<String> classes,
                      List<String> tags, @Nullable NiagaraBounds worldBounds) {
            this(identity, component, sockets, classes, tags, worldBounds, ComponentTickWindow.DEFAULT);
        }

        public Source(String identity, @Nullable Transform component, List<Socket> sockets,
                      List<String> classes, List<String> tags) {
            this(identity, component, sockets, classes, tags, null);
        }

        public Source {
            Objects.requireNonNull(tickWindow);
            if (identity.isBlank()) throw new IllegalArgumentException("A Niagara socket source requires a stable identity");
            sockets = List.copyOf(sockets);
            classes = List.copyOf(classes);
            tags = List.copyOf(tags);
        }
    }

    private static Map<String, Source> named(Map<String, Source> source) {
        var values = new TreeMap<String, Source>(SourceNames.ASCII_CASE_INSENSITIVE);
        values.putAll(Map.copyOf(source));
        if (values.size() != source.size()) throw new IllegalArgumentException("Duplicate Niagara socket source name");
        return Collections.unmodifiableMap(values);
    }
}
