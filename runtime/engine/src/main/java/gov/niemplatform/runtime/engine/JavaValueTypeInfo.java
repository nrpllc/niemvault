package gov.niemplatform.runtime.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Objects;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.SimpleTypeSerializerSnapshot;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshot;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;

/**
 * Moves the platform's immutable value types across Flink operator boundaries using Java
 * serialisation.
 *
 * <p>Flink would otherwise fall back to Kryo, and the Kryo version Flink 1.20 ships cannot
 * serialise Java records at all -- it fails with {@code can't get field offset on a record class}.
 * Every value type in this platform is a record, so that fallback is not usable.
 *
 * <p>Choosing Java serialisation explicitly, rather than reaching for a Kryo workaround, is
 * deliberate. Determinism is a correctness property here: acceptance criterion 6 compares replayed
 * silver against the original and criterion 7 compares batch output against streaming, and both
 * comparisons are only meaningful if a record survives an operator boundary exactly. Java
 * serialisation is slower than Kryo and exactly faithful, which is the correct trade for records
 * carrying criminal justice data.
 *
 * <p>Applies only to the platform's own {@link Serializable} value types. Nothing here is a
 * general-purpose serialisation strategy.
 */
public final class JavaValueTypeInfo<T extends Serializable> extends TypeInformation<T> {

    private static final long serialVersionUID = 1L;

    private final Class<T> type;

    private JavaValueTypeInfo(Class<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Type information for one of the platform's immutable value types.
     *
     * <p>Named {@code forValue} rather than {@code of} because {@code TypeInformation.of} is a
     * static method on the supertype, and two statics with the same erasure would clash.
     */
    public static <T extends Serializable> JavaValueTypeInfo<T> forValue(Class<T> type) {
        return new JavaValueTypeInfo<>(type);
    }

    @Override
    public boolean isBasicType() {
        return false;
    }

    @Override
    public boolean isTupleType() {
        return false;
    }

    @Override
    public int getArity() {
        return 1;
    }

    @Override
    public int getTotalFields() {
        return 1;
    }

    @Override
    public Class<T> getTypeClass() {
        return type;
    }

    @Override
    public boolean isKeyType() {
        // Our value types have well-defined equals and hashCode but no natural ordering, so they
        // are usable as keys but not as sort keys. Flink asks this question separately.
        return true;
    }

    @Override
    public TypeSerializer<T> createSerializer(ExecutionConfig config) {
        return new JavaValueSerializer<>(type);
    }

    @Override
    public String toString() {
        return "JavaValue(" + type.getSimpleName() + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JavaValueTypeInfo<?> info && type.equals(info.type);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public boolean canEqual(Object other) {
        return other instanceof JavaValueTypeInfo;
    }

    /** The serializer itself. Stateless, so duplication is free. */
    public static final class JavaValueSerializer<T extends Serializable> extends TypeSerializer<T> {

        private static final long serialVersionUID = 1L;

        private final Class<T> type;

        JavaValueSerializer(Class<T> type) {
            this.type = type;
        }

        @Override
        public boolean isImmutableType() {
            // Every value type this serves is immutable, which lets Flink skip defensive copying.
            return true;
        }

        @Override
        public TypeSerializer<T> duplicate() {
            return this;
        }

        @Override
        public T createInstance() {
            return null;
        }

        @Override
        public T copy(T from) {
            return from;
        }

        @Override
        public T copy(T from, T reuse) {
            return from;
        }

        @Override
        public int getLength() {
            return -1;
        }

        @Override
        public void serialize(T value, DataOutputView target) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(value);
            }
            byte[] serialised = bytes.toByteArray();
            target.writeInt(serialised.length);
            target.write(serialised);
        }

        @Override
        public T deserialize(DataInputView source) throws IOException {
            int length = source.readInt();
            byte[] serialised = new byte[length];
            source.readFully(serialised);
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serialised))) {
                return type.cast(in.readObject());
            } catch (ClassNotFoundException e) {
                throw new IOException(
                        "Cannot deserialise a " + type.getName() + " crossing an operator boundary", e);
            }
        }

        @Override
        public T deserialize(T reuse, DataInputView source) throws IOException {
            return deserialize(source);
        }

        @Override
        public void copy(DataInputView source, DataOutputView target) throws IOException {
            int length = source.readInt();
            byte[] serialised = new byte[length];
            source.readFully(serialised);
            target.writeInt(length);
            target.write(serialised);
        }

        @Override
        public TypeSerializerSnapshot<T> snapshotConfiguration() {
            return new Snapshot<>(type);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof JavaValueSerializer<?> serializer && type.equals(serializer.type);
        }

        @Override
        public int hashCode() {
            return type.hashCode();
        }

        /** Snapshot for state compatibility. The format is Java serialisation and does not vary. */
        public static final class Snapshot<T extends Serializable>
                extends SimpleTypeSerializerSnapshot<T> {

            public Snapshot() {
                // Required no-arg constructor: Flink instantiates snapshots reflectively when
                // restoring state, before it knows which type they describe.
                super(() -> new JavaValueSerializer<>(null));
            }

            Snapshot(Class<T> type) {
                super(() -> new JavaValueSerializer<>(type));
            }
        }
    }
}
