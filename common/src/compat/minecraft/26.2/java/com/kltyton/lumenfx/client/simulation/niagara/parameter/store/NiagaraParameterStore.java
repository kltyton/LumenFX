package com.kltyton.lumenfx.client.simulation.niagara.parameter.store;

import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.BoundOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/** Bound stores copy immediately once, then propagate only the source's dirty categories at Tick. */
public final class NiagaraParameterStore implements AutoCloseable {
    public record ObjectBinding(boolean valid, @Nullable Object value) { }
    private static final int NUMERIC = 1, INTERFACE = 2, OBJECT = 4, ALL = NUMERIC | INTERFACE | OBJECT;
    private record Slot(NiagaraParameterLayout.Variable variable, int offset, int index) { }
    private record Binding(NiagaraParameterStore destination, List<BoundOffset> pairs) { }
    private final NiagaraParameterLayout layout;
    private final Map<Key, Slot> slots = new LinkedHashMap<>();
    private final byte[] numeric;
    private final ByteBuffer numericWords;
    private final List<Slot> sorted;
    private final Map<Key, Key> userRedirects = new LinkedHashMap<>();
    private final @Nullable Object[] interfaces, objects;
    private final List<Binding> bindings = new ArrayList<>();
    private final List<NiagaraParameterStore> sources = new ArrayList<>();
    private int dirty;
    private long interfacesRevision;
    private boolean closed;

    public NiagaraParameterStore(NiagaraParameterLayout layout) {
        this(layout, false);
    }
    public NiagaraParameterStore(NiagaraParameterLayout layout, boolean redirectUsers) {
        sorted = new ArrayList<>();
        int bytes = 0, interfaceCount = 0, objectCount = 0;
        for (var variable : layout.variables()) {
            int insertion = search(variable.key(), false);
            if (insertion >= 0) continue;
            int offset = variable.key().storage() == Storage.NUMERIC ? variable.offset() >= 0 ? variable.offset() : bytes : -1;
            int index = -1;
            if (variable.key().storage() == Storage.INTERFACE) {
                index = variable.objectIndex() >= 0 ? variable.objectIndex() : interfaceCount;
                interfaceCount = Math.max(interfaceCount, Math.addExact(index, 1));
            } else if (variable.key().storage() == Storage.OBJECT) {
                index = variable.objectIndex() >= 0 ? variable.objectIndex() : objectCount;
                objectCount = Math.max(objectCount, Math.addExact(index, 1));
            }
            Slot slot = new Slot(variable, offset, index);
            slots.put(variable.key(), slot);
            sorted.add(~insertion, slot);
            if (offset >= 0) bytes = Math.max(bytes, Math.addExact(offset, variable.bytes()));
        }
        if (layout.numericBytes() >= 0) {
            if (bytes > layout.numericBytes()) throw new IllegalArgumentException("Parameter declaration exceeds its data array");
            bytes = layout.numericBytes();
        }
        if (layout.interfaceCount() >= 0) {
            if (interfaceCount > layout.interfaceCount()) throw new IllegalArgumentException("DI declaration exceeds its source array");
            interfaceCount = layout.interfaceCount();
        }
        if (layout.objectCount() >= 0) {
            if (objectCount > layout.objectCount()) throw new IllegalArgumentException("UObject declaration exceeds its source array");
            objectCount = layout.objectCount();
        }
        this.layout = new NiagaraParameterLayout(slots.values().stream().map(Slot::variable).toList(), layout.numericBytes(),
                layout.interfaceCount(), layout.objectCount());
        if (redirectUsers) for (var slot : sorted) {
            Key key = slot.variable().key();
            if (key.name().length() > 5 && SourceNames.equal(key.name().substring(0, 5), "User."))
                userRedirects.put(new Key(key.name().substring(5), key.sourceType(), key.underlyingType(), key.flags(), key.storage()), key);
        }
        numeric = new byte[bytes]; numericWords = ByteBuffer.wrap(numeric).order(ByteOrder.LITTLE_ENDIAN);
        interfaces = new Object[interfaceCount]; objects = new Object[objectCount];
        dirty = ALL;
    }

    public NiagaraParameterLayout layout() { return layout; }
    public boolean contains(Key key) { requireOpen(); return find(key, false) != null; }
    public boolean numericDirty() { requireOpen(); return (dirty & NUMERIC) != 0; }
    public boolean interfacesDirty() { requireOpen(); return (dirty & INTERFACE) != 0; }
    public long interfacesRevision() { requireOpen(); return interfacesRevision; }
    public void forEachDataInterface(BiConsumer<Key, @Nullable Object> action) {
        requireOpen();
        for (Slot slot : sorted) if (slot.variable().key().storage() == Storage.INTERFACE)
            action.accept(slot.variable().key(), interfaces[slot.index()]);
    }
    public void snapshotValues(BiConsumer<Key, byte[]> numbers, BiConsumer<Key, @Nullable Object> references) {
        requireOpen();
        for (var slot : sorted) {
            var variable = slot.variable();
            var key = variable.key();
            switch (key.storage()) {
                case NUMERIC -> {
                    if (variable.bytes() > 0)
                        numbers.accept(key, Arrays.copyOfRange(numeric, slot.offset(), slot.offset() + variable.bytes()));
                }
                case INTERFACE -> references.accept(key, interfaces[slot.index()]);
                case OBJECT -> references.accept(key, objects[slot.index()]);
            }
        }
    }
    public boolean objectsDirty() { requireOpen(); return (dirty & OBJECT) != 0; }
    public void markNumericDirty() { requireOpen(); dirty |= NUMERIC; }
    public void markInterfacesDirty() {
        requireOpen();
        interfacesRevision++;
        dirty |= INTERFACE;
    }

    /** Dataset bindings use the source's IgnoreType binary lookup before resolving the numeric byte offset. */
    public int dataSetNumericOffset(String name) {
        requireOpen();
        Slot slot = find(new Key(name, "", 2, 0, Storage.NUMERIC), true);
        if (slot == null) return -1;
        requireStorage(slot, Storage.NUMERIC);
        return slot.variable().bytes() == 0 ? -1 : slot.offset();
    }
    private @Nullable Slot find(Key key, boolean ignoreType) {
        if (ignoreType) {
            for (var redirect : userRedirects.entrySet()) if (SourceNames.equal(redirect.getKey().name(), key.name())) {
                key = redirect.getValue(); ignoreType = false; break;
            }
        } else {
            Key redirect = userRedirects.get(key);
            if (redirect != null) key = redirect;
        }
        int index = search(key, ignoreType);
        return index >= 0 ? sorted.get(index) : null;
    }
    private int search(Key key, boolean ignoreType) {
        int start = 0, count = sorted.size();
        while (count != 0) {
            int leftover = count % 2;
            count /= 2;
            int check = start + count;
            Slot slot = sorted.get(check);
            int comparison = SourceNames.lexicalCompare(slot.variable().key().name(), key.name());
            if (comparison == 0 && !ignoreType) comparison = compareTypes(slot.variable().key(), key);
            if (comparison < 0) { start = check + 1; count += leftover - 1; }
            else if (comparison == 0) return check;
        }
        return ~start;
    }
    public int numericWord(int offset) { requireOpen(); return numericWords.getInt(offset); }
    public void numericWord(int offset, int bits) { requireOpen(); numericWords.putInt(offset, bits); dirty |= NUMERIC; }
    public void copyNumericRange(int sourceOffset, int destinationOffset, int bytes) {
        requireOpen();
        System.arraycopy(numeric, sourceOffset, numeric, destinationOffset, bytes);
    }
    public int numericBytes(Key key) {
        requireOpen();
        Slot slot = find(key, false);
        if (slot == null) return -1;
        requireStorage(slot, Storage.NUMERIC);
        return slot.variable().bytes();
    }
    public int numericOffset(Key key) { return requireSlot(key, Storage.NUMERIC).offset(); }
    public void setNumericData(byte[] data) {
        requireOpen();
        if (data.length != numeric.length) throw new IllegalArgumentException("Parameter data length differs from its store");
        System.arraycopy(data, 0, numeric, 0, data.length);
        dirty |= NUMERIC;
    }
    public byte[] numericData() { requireOpen(); return numeric.clone(); }
    public void copyNumericData(int sourceOffset, byte[] destination, int destinationOffset, int bytes) {
        requireOpen();
        System.arraycopy(numeric, sourceOffset, destination, destinationOffset, bytes);
    }
    public void copyValuesFrom(NiagaraParameterStore source) {
        requireOpen(); source.requireOpen();
        if (!layout.equals(source.layout)) throw new IllegalArgumentException("Parameter clone requires the same source declarations");
        System.arraycopy(source.numeric, 0, numeric, 0, numeric.length);
        System.arraycopy(source.interfaces, 0, interfaces, 0, interfaces.length);
        System.arraycopy(source.objects, 0, objects, 0, objects.length);
        interfacesRevision++;
        dirty = ALL;
    }

    public @Nullable Integer readRendererInteger(String name, boolean bool) {
        requireOpen();
        Slot slot = find(new Key(name, "", 2, 0, Storage.NUMERIC), true);
        if (slot == null) return null;
        requireStorage(slot, Storage.NUMERIC);
        String sourceType = slot.variable().key().sourceType();
        if (slot.variable().bytes() != Integer.BYTES || !sourceType.endsWith(bool ? ".NiagaraBool" : ".NiagaraInt32"))
            throw new IllegalArgumentException("Incompatible Niagara renderer binding type " + name);
        return numericWord(slot.offset());
    }

    public boolean readRendererFloating(String name, float[] output) {
        requireOpen();
        Slot slot = find(new Key(name, "", 2, 0, Storage.NUMERIC), true);
        if (slot == null) return false;
        requireStorage(slot, Storage.NUMERIC);
        int bytes = Math.multiplyExact(output.length, Float.BYTES);
        if (slot.variable().bytes() != bytes)
            throw new IllegalArgumentException("Incompatible Niagara renderer binding " + name);
        String sourceType = slot.variable().key().sourceType();
        if (sourceType.endsWith(".NiagaraBool") || sourceType.endsWith(".NiagaraInt32"))
            throw new IllegalArgumentException("Incompatible Niagara renderer binding type " + name);
        for (int index = 0; index < output.length; index++)
            output[index] = Float.intBitsToFloat(numericWord(slot.offset() + index * Float.BYTES));
        return true;
    }

    public boolean copyNumeric(Key key, byte[] destination, int offset) {
        requireOpen();
        Slot slot = find(key, false);
        if (slot == null) return false;
        requireStorage(slot, Storage.NUMERIC);
        int bytes = slot.variable().bytes();
        if (bytes == 0) return false;
        System.arraycopy(numeric, slot.offset(), destination, offset, bytes);
        return true;
    }

    public void setNumeric(Key key, byte[] source, int offset, int bytes) {
        Slot slot = requireSlot(key, Storage.NUMERIC);
        if (bytes != slot.variable().bytes()) throw new IllegalArgumentException("Niagara parameter width mismatch: " + key.name());
        System.arraycopy(source, offset, numeric, slot.offset(), bytes);
        dirty |= NUMERIC;
    }

    Key canonicalInterfaceKey(Key key) {
        return requireSlot(key, Storage.INTERFACE).variable().key();
    }

    public @Nullable Object dataInterface(Key key) {
        Slot slot = requireSlot(key, Storage.INTERFACE);
        return interfaces[slot.index()];
    }
    public @Nullable Object resolveInterface(Key key, @Nullable NiagaraParameterStore instance, @Nullable Key instanceKey) {
        return instance != null && instanceKey != null && instance.contains(instanceKey)
                ? instance.dataInterface(instanceKey) : dataInterface(key);
    }
    public int dataInterfaceCount() {
        requireOpen();
        return interfaces.length;
    }
    public @Nullable Object dataInterface(int index) {
        requireOpen();
        return interfaces[index];
    }
    public @Nullable Key findVariableFromDataInterface(Object value) {
        requireOpen();
        for (int index = 0; index < interfaces.length; index++) if (interfaces[index] == value) {
            for (Slot slot : sorted) if (slot.variable().key().storage() == Storage.INTERFACE && slot.index() == index)
                return value instanceof NiagaraDataInterfaceObject object && object.matchesSourceType(slot.variable().key())
                        ? slot.variable().key() : null;
            return null;
        }
        return null;
    }
    public @Nullable Object object(Key key) {
        Slot slot = requireSlot(key, Storage.OBJECT);
        return objects[slot.index()];
    }
    public ObjectBinding objectBinding(String name) {
        requireOpen();
        Slot slot = find(new Key(name, "", 2, 0, Storage.NUMERIC), true);
        return slot != null && slot.variable().key().storage() == Storage.OBJECT
                ? new ObjectBinding(true, objects[slot.index()]) : new ObjectBinding(false, null);
    }
    public void setDataInterface(Key key, @Nullable Object value) {
        Slot slot = requireSlot(key, Storage.INTERFACE);
        setDataInterface(slot.index(), value);
    }
    public void setDataInterface(int index, @Nullable Object value) {
        requireOpen();
        interfaces[index] = value;
        interfacesRevision++;
        dirty |= INTERFACE;
    }
    public void setObject(Key key, @Nullable Object value) {
        Slot slot = requireSlot(key, Storage.OBJECT);
        setObject(slot.index(), value);
    }
    public void setObject(int index, @Nullable Object value) {
        requireOpen();
        objects[index] = value;
        dirty |= OBJECT;
    }

    public void bindTo(NiagaraParameterStore destination) {
        requireOpen(); destination.requireOpen();
        for (var binding : bindings) if (binding.destination() == destination) return;
        var pairs = new ArrayList<BoundOffset>();
        if (sorted.isEmpty() || destination.sorted.isEmpty()) return;
        if (NativeParameterStoreMath.useMerge(sorted.size(), destination.sorted.size())) {
            int sourceIndex = 0, targetIndex = 0;
            while (sourceIndex < sorted.size() && targetIndex < destination.sorted.size()) {
                Slot source = sorted.get(sourceIndex), target = destination.sorted.get(targetIndex);
                int comparison = SourceNames.lexicalCompare(source.variable().key().name(), target.variable().key().name());
                if (comparison == 0) {
                    int types = compareTypes(source.variable().key(), target.variable().key());
                    if (types != 0 && !lwcMatch(source, target)) comparison = types;
                }
                if (comparison < 0) sourceIndex++;
                else if (comparison > 0) targetIndex++;
                else { pair(pairs, source, target); sourceIndex++; targetIndex++; }
            }
        } else if (destination.sorted.size() <= sorted.size()) {
            for (var target : destination.sorted) {
                Slot source = find(target.variable().key(), true);
                if (source != null) pair(pairs, source, target);
            }
        } else {
            for (var source : sorted) {
                Slot target = destination.find(source.variable().key(), true);
                if (target != null) pair(pairs, source, target);
            }
        }
        bind(destination, pairs);
    }

    void bindTo(NiagaraParameterStore destination, List<BoundOffset> offsets) {
        requireOpen(); destination.requireOpen();
        for (var binding : bindings) if (binding.destination() == destination) return;
        for (BoundOffset offset : offsets) {
            if (offset.source() >= capacity(offset) || offset.target() >= destination.capacity(offset))
                throw new IllegalArgumentException("Cooked Niagara bound parameter exceeds its " + offset.storage() + " store");
        }
        bind(destination, offsets);
    }

    private int capacity(BoundOffset offset) {
        return switch (offset.storage()) {
            case NUMERIC -> numeric.length - offset.bytes() + 1;
            case INTERFACE -> interfaces.length;
            case OBJECT -> objects.length;
        };
    }

    private void bind(NiagaraParameterStore destination, List<BoundOffset> offsets) {
        if (offsets.isEmpty()) return;
        Binding binding = new Binding(destination, List.copyOf(offsets));
        copy(binding, ALL);
        bindings.add(binding);
        destination.sources.add(this);
    }

    private static int compareTypes(Key left, Key right) {
        return SourceNames.lexicalCompare(left.storeTypeName(), right.storeTypeName());
    }
    private static boolean lwcMatch(Slot source, Slot destination) {
        Boolean a = source.variable().lwc(), b = destination.variable().lwc();
        if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) return true;
        if (a == null || b == null)
            throw new IllegalArgumentException("Niagara cross-type store binding needs source LWC field metadata: "
                    + source.variable().key().name() + " (" + source.variable().key().sourceType() + ", "
                    + destination.variable().key().sourceType() + ")");
        return false;
    }
    private static void pair(List<BoundOffset> result, Slot source, Slot destination) {
        Storage storage = destination.variable().key().storage();
        requireStorage(source, storage);
        if (storage == Storage.NUMERIC) {
            if (source.variable().bytes() == 0 || destination.variable().bytes() == 0) return;
            result.add(new BoundOffset(storage, source.offset(), destination.offset(), destination.variable().bytes()));
        } else {
            result.add(new BoundOffset(storage, source.index(), destination.index(), 0));
        }
    }

    public void unbind(NiagaraParameterStore destination) {
        requireOpen();
        if (bindings.removeIf(binding -> binding.destination() == destination)) destination.sources.remove(this);
    }

    public void tick() {
        requireOpen();
        if (dirty != 0) for (var binding : bindings) copy(binding, dirty);
        dirty = 0;
    }

    public void resetValues() {
        requireOpen();
        Arrays.fill(numeric, (byte)0); Arrays.fill(interfaces, null); Arrays.fill(objects, null);
        interfacesRevision++;
        dirty = ALL;
    }

    private void copy(Binding binding, int categories) {
        NiagaraParameterStore target = binding.destination();
        target.requireOpen();
        for (BoundOffset pair : binding.pairs()) {
            switch (pair.storage()) {
                case NUMERIC -> {
                    if ((categories & NUMERIC) == 0) continue;
                    System.arraycopy(numeric, pair.source(), target.numeric, pair.target(), pair.bytes());
                    target.dirty |= NUMERIC;
                }
                case INTERFACE -> {
                    if ((categories & INTERFACE) == 0) continue;
                    target.interfaces[pair.target()] = interfaces[pair.source()];
                    target.interfacesRevision++;
                    target.dirty |= INTERFACE;
                }
                case OBJECT -> {
                    if ((categories & OBJECT) == 0) continue;
                    target.objects[pair.target()] = objects[pair.source()];
                    target.dirty |= OBJECT;
                }
            }
        }
    }

    private Slot requireSlot(Key key, Storage storage) {
        requireOpen();
        Slot slot = find(key, false);
        if (slot == null) throw new IllegalArgumentException("Missing Niagara parameter slot: " + key.name());
        requireStorage(slot, storage);
        return slot;
    }
    private static void requireStorage(Slot slot, Storage storage) {
        if (slot.variable().key().storage() != storage) throw new IllegalArgumentException("Wrong Niagara parameter storage: " + slot.variable().key().name());
    }
    private void requireOpen() {
        if (closed) throw new IllegalStateException("Niagara parameter store is closed");
    }

    @Override public void close() {
        if (closed) return;
        for (var source : List.copyOf(sources)) source.unbind(this);
        for (var binding : bindings) binding.destination().sources.remove(this);
        bindings.clear(); sources.clear();
        Arrays.fill(interfaces, null); Arrays.fill(objects, null);
        closed = true;
    }
}
