package gov.niemplatform.storage.iceberg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * How to reach the canonical silver store.
 *
 * <p>Metadata lives in a JDBC catalog and data in an object store, with no Hadoop filesystem on
 * either path (ADR 0005). That combination is what lets silver run on a developer's machine, in a
 * container, and in an air-gapped data centre with an on-premises object store, from one code path.
 *
 * @param catalogName catalog identifier, recorded in table metadata
 * @param jdbcUri JDBC connection string for catalog metadata
 * @param warehouse object store location for table data, e.g. {@code s3://niem-silver/silver}
 * @param s3Endpoint object store endpoint; an on-premises store or MinIO rather than AWS
 * @param accessKeyId object store credential
 * @param secretAccessKey object store credential
 * @param region region label the client sends; arbitrary for a non-AWS store but required
 */
public record IcebergCanonicalStoreConfig(
        String catalogName,
        String jdbcUri,
        String warehouse,
        String s3Endpoint,
        String accessKeyId,
        String secretAccessKey,
        String region) {

    public IcebergCanonicalStoreConfig {
        Objects.requireNonNull(catalogName, "catalogName");
        Objects.requireNonNull(jdbcUri, "jdbcUri");
        Objects.requireNonNull(warehouse, "warehouse");
        region = region == null || region.isBlank() ? "us-east-1" : region;
    }

    /** Iceberg catalog properties, ready to hand to {@code JdbcCatalog.initialize}. */
    Map<String, String> catalogProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(org.apache.iceberg.CatalogProperties.CATALOG_IMPL,
                org.apache.iceberg.jdbc.JdbcCatalog.class.getName());
        properties.put(org.apache.iceberg.CatalogProperties.URI, jdbcUri);
        properties.put(org.apache.iceberg.CatalogProperties.WAREHOUSE_LOCATION, warehouse);
        properties.put(org.apache.iceberg.CatalogProperties.FILE_IO_IMPL,
                org.apache.iceberg.aws.s3.S3FileIO.class.getName());

        endpoint().ifPresent(endpoint -> {
            properties.put("s3.endpoint", endpoint);
            // Required for MinIO and most on-premises stores, which do not do virtual-host
            // addressing. Harmless against AWS.
            properties.put("s3.path-style-access", "true");
        });
        if (accessKeyId != null && secretAccessKey != null) {
            properties.put("s3.access-key-id", accessKeyId);
            properties.put("s3.secret-access-key", secretAccessKey);
        }
        properties.put("client.region", region);
        return properties;
    }

    /**
     * The endpoint, when one is configured.
     *
     * <p>Named {@code endpoint} rather than {@code s3Endpoint} because a record component's
     * accessor must return the component's own type, and this narrows it to an Optional.
     */
    public Optional<String> endpoint() {
        return Optional.ofNullable(s3Endpoint).filter(value -> !value.isBlank());
    }

    /**
     * Property names only -- never values.
     *
     * <p>This record holds object store credentials. Same obligation as everywhere else; see
     * ADR 0015.
     */
    @Override
    public String toString() {
        return "IcebergCanonicalStoreConfig[catalog=%s, warehouse=%s, endpoint=%s, credentials=%s]"
                .formatted(catalogName, warehouse, endpoint().orElse("(default)"),
                        accessKeyId == null ? "(none)" : "(set)");
    }
}
