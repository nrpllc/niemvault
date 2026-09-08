package gov.niemplatform.storage.parquet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.meta.TenantId;
import gov.niemplatform.storage.api.BronzeBatch;
import gov.niemplatform.storage.api.BronzeStorageException;
import gov.niemplatform.storage.api.RawEnvelope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A bronze store holds one agency's records and refuses anyone else's (ADR 0026).
 *
 * <p>This is the check behind the claim the product makes. "Your records are in your own database"
 * is something an agency's counsel can verify; "every query filters correctly by tenant" is a promise
 * about code that a lawyer cannot audit and a court will not take on faith. The difference between
 * the two is whether something like this exists.
 *
 * <p>Enforced when written rather than filtered when read, deliberately. Two agencies' raw records
 * interleaved in one store is not a condition to detect later: bronze is append-only, so there is no
 * undoing it, and the raw record of what a source sent is the one thing the platform promises never
 * to rewrite.
 */
class BronzeBelongsToOneTenantTest {

    private static final TenantId COUNTY = TenantId.of("co.riverton.pd");
    private static final TenantId STATE = TenantId.of("st.colorado.cbi");

    @TempDir
    Path work;

    private Path root;

    private static BronzeBatch batch(String sourceId) {
        RawEnvelope envelope = new RawEnvelope(
                sourceId,
                "cad-export",
                Instant.parse("2026-03-04T18:20:00Z"),
                null,
                "2026-000114,BURG".getBytes(StandardCharsets.UTF_8),
                gov.niemplatform.storage.api.SourceOffset.of("incidents.csv#000001"));
        return new BronzeBatch(sourceId, "cad-export", List.of(envelope));
    }

    private void land(TenantId tenant) {
        try (var store = new ParquetBronzeStore(root, tenant)) {
            store.append(batch("riverton-pd-cad"));
        }
    }

    @Nested
    @DisplayName("Claiming a store")
    class Claiming {

        @Test
        @DisplayName("an empty root is claimed by the first agency to use it")
        void claimsAnEmptyRoot() throws IOException {
            root = work.resolve("bronze");
            land(COUNTY);

            assertThat(Files.readString(root.resolve(".tenant"), StandardCharsets.UTF_8).trim())
                    .isEqualTo(COUNTY.value());
        }

        @Test
        @DisplayName("the same agency may reopen its own store")
        void reopensItsOwn() {
            root = work.resolve("bronze");
            land(COUNTY);
            land(COUNTY);

            try (var store = new ParquetBronzeStore(root, COUNTY)) {
                assertThat(store.tenant()).isEqualTo(COUNTY);
            }
        }

        @Test
        @DisplayName("a store that already holds data but names nobody is not adopted silently")
        void refusesToAdoptUnclaimedData() throws IOException {
            // Stamping an agency's name on data of unknown origin is worse than refusing to touch
            // it: it asserts a provenance nobody recorded, in the one store that is meant to be the
            // record of what actually arrived.
            root = Files.createDirectories(work.resolve("legacy"));
            Files.createDirectories(root.resolve("riverton-pd-cad"));

            assertThatThrownBy(() -> new ParquetBronzeStore(root, COUNTY))
                    .isInstanceOf(BronzeStorageException.class)
                    .hasMessageContaining("does not say whose it is");
        }
    }

    @Nested
    @DisplayName("Refusing another agency")
    class Refusing {

        @Test
        @DisplayName("a second agency cannot open a store that already belongs to one")
        void refusesADifferentTenant() {
            // The failure this prevents. Before it, two runs against one bronze root under different
            // tenants interleaved two agencies' raw data and nothing noticed.
            root = work.resolve("bronze");
            land(COUNTY);

            assertThatThrownBy(() -> new ParquetBronzeStore(root, STATE))
                    .isInstanceOf(BronzeStorageException.class)
                    .hasMessageContaining(COUNTY.value())
                    .hasMessageContaining(STATE.value())
                    .hasMessageContaining("serves one agency");
        }

        @Test
        @DisplayName("it refuses on open, before anything of the second agency's is written")
        void refusesBeforeWriting() throws IOException {
            root = work.resolve("bronze");
            land(COUNTY);
            long before = Files.walk(root).count();

            assertThatThrownBy(() -> new ParquetBronzeStore(root, STATE))
                    .isInstanceOf(BronzeStorageException.class);

            assertThat(Files.walk(root).count())
                    .as("nothing of the second agency's reached an append-only store")
                    .isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Reading a store you did not create")
    class Reading {

        @Test
        @DisplayName("adopts the tenant the store declares, rather than guessing one")
        void adoptsTheDeclaredTenant() {
            root = work.resolve("bronze");
            land(COUNTY);

            try (var store = ParquetBronzeStore.openExisting(root)) {
                assertThat(store.tenant()).isEqualTo(COUNTY);
            }
        }

        @Test
        @DisplayName("refuses a store that does not say whose data it is")
        void refusesAnUnlabelledStore() throws IOException {
            // A reader that guesses is a reader that reports one agency's data as another's.
            root = Files.createDirectories(work.resolve("unlabelled"));

            assertThatThrownBy(() -> ParquetBronzeStore.openExisting(root))
                    .isInstanceOf(BronzeStorageException.class)
                    .hasMessageContaining("does not declare a tenant");
        }
    }
}
