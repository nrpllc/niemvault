package gov.niemplatform.cli;

import gov.niemplatform.storage.api.CanonicalStore;
import gov.niemplatform.storage.iceberg.IcebergCanonicalStore;
import gov.niemplatform.storage.iceberg.IcebergCanonicalStoreConfig;
import java.util.Optional;
import picocli.CommandLine.Option;

/**
 * Where the canonical store lives, for every command that writes to it.
 *
 * <p>Shared as a mixin rather than declared twice. {@code run} and {@code replay} write to the same
 * store and must be pointed at it identically; two copies of these options would eventually disagree
 * about a default and send an ingest and its replay to different warehouses, which is the one thing
 * that would make criterion 6 unprovable in production.
 *
 * <h2>Secrets come from the environment</h2>
 *
 * <p>An option is written to shell history and is visible in the process list to every user on the
 * machine. That is not a defensible way to handle credentials for a system holding criminal justice
 * data, so the object store secret is read from {@code NIEM_S3_SECRET_ACCESS_KEY} and never accepted
 * on a command line.
 */
final class SilverOptions {

    /** Environment variable holding the object store secret. */
    static final String S3_SECRET = "NIEM_S3_SECRET_ACCESS_KEY";

    @Option(names = "--silver-catalog-uri",
            description = "JDBC URI of the Iceberg catalogue, e.g. jdbc:h2:file:/data/catalog. "
                    + "Omitted: canonical records are mapped but not stored.")
    String catalogUri;

    @Option(names = "--silver-warehouse",
            description = "Iceberg warehouse location, e.g. s3://silver/warehouse.")
    String warehouse;

    @Option(names = "--silver-catalog-name", defaultValue = "niem",
            description = "Catalogue name. Default: ${DEFAULT-VALUE}")
    String catalogName;

    @Option(names = "--s3-endpoint",
            description = "Object store endpoint. Omitted: the AWS default for the region.")
    String endpoint;

    @Option(names = "--s3-access-key-id",
            description = "Object store access key id. The secret is read from $" + S3_SECRET + ".")
    String accessKeyId;

    @Option(names = "--s3-region", defaultValue = "us-east-1",
            description = "Object store region. Default: ${DEFAULT-VALUE}")
    String region;

    /** Whether the operator asked for silver at all. */
    boolean requested() {
        return catalogUri != null || warehouse != null;
    }

    /**
     * Why these options cannot be used, if they cannot.
     *
     * <p>Reported before anything is landed. Discovering a missing credential after a feed has been
     * ingested but before it could be stored leaves an operator with bronze that has to be replayed
     * to catch up — recoverable, but only because bronze exists, and not a state to walk into.
     */
    Optional<String> problem() {
        if (!requested()) {
            return Optional.empty();
        }
        if (catalogUri == null || warehouse == null) {
            return Optional.of("--silver-catalog-uri and --silver-warehouse are given together");
        }
        if (accessKeyId != null && System.getenv(S3_SECRET) == null) {
            return Optional.of("--s3-access-key-id was given but $" + S3_SECRET + " is not set");
        }
        return Optional.empty();
    }

    /** Opens the store, or nothing if silver was not asked for. */
    Optional<CanonicalStore> open() {
        if (!requested()) {
            return Optional.empty();
        }
        return Optional.of(new IcebergCanonicalStore(new IcebergCanonicalStoreConfig(
                catalogName, catalogUri, warehouse,
                endpoint, accessKeyId, System.getenv(S3_SECRET), region)));
    }

    /** How the store is described in a run summary. */
    String describe() {
        return requested() ? warehouse + " in " + catalogUri : "(not configured)";
    }
}
