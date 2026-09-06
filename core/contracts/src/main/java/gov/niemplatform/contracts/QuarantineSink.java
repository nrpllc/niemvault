package gov.niemplatform.contracts;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where records that failed a contract go (spec §4.2).
 *
 * <p>The sink assigns the quarantine identifier and returns it, so the identifier can be carried
 * on the violation event -- an event saying a record was rejected without saying where to find
 * it is not actionable.
 *
 * <p>Implementations must be durable wherever they are used in anger; quarantine is part of the
 * audit surface, not a debugging convenience.
 */
public interface QuarantineSink extends Serializable {

    /**
     * Accepts a rejected record and returns its quarantine identifier.
     *
     * <p>Must not throw. A quarantine sink that can fail turns a single bad record into a stopped
     * pipeline, which is the outcome spec §4.2 rules out. An implementation that cannot store the
     * record returns an identifier marking it as lost rather than propagating a failure.
     */
    String quarantine(QuarantinedRecord rejected);

    /** An in-memory sink, for tests and for a single CLI run. */
    final class InMemory implements QuarantineSink {

        private static final long serialVersionUID = 1L;

        private final transient List<QuarantinedRecord> held = new CopyOnWriteArrayList<>();
        private final AtomicLong sequence = new AtomicLong();

        @Override
        public String quarantine(QuarantinedRecord rejected) {
            String id = "q-" + sequence.incrementAndGet();
            held.add(rejected);
            return id;
        }

        /** Everything quarantined so far, in arrival order. */
        public List<QuarantinedRecord> held() {
            return List.copyOf(held);
        }

        /** Records quarantined at a given hop. */
        public List<QuarantinedRecord> heldAt(String hopId) {
            List<QuarantinedRecord> matched = new ArrayList<>();
            held.forEach(record -> {
                if (record.hopId().equals(hopId)) {
                    matched.add(record);
                }
            });
            return List.copyOf(matched);
        }

        public int size() {
            return held.size();
        }
    }
}
