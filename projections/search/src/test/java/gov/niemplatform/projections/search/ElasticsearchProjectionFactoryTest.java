package gov.niemplatform.projections.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.niemplatform.canonical.meta.TenantId;
import gov.niemplatform.projections.api.ProjectionContext;
import gov.niemplatform.projections.api.ProjectionDefinition;
import gov.niemplatform.projections.api.ProjectionDefinitionException;
import gov.niemplatform.projections.api.ProjectionRegistry;
import gov.niemplatform.projections.api.ProjectionType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Settings are checked before anything is contacted, so none of this needs a cluster. */
class ElasticsearchProjectionFactoryTest {

    private static final ProjectionContext CONTEXT = new ProjectionContext(
            TenantId.of("us.fl.leon-so"), "leon-so-cad", "leon-cad-to-canonical", "1.0.0",
            List.of(), variable -> null);

    private static ProjectionDefinition definition(Map<String, String> settings) {
        return new ProjectionDefinition("leon-search", "1.0.0", ProjectionType.SEARCH, settings);
    }

    @Test
    void theFactoryIsDiscoveredForTheSearchType() {
        assertThat(ProjectionRegistry.discover().availableTypes()).contains(ProjectionType.SEARCH);
    }

    @Test
    void everySettingProblemIsReportedAtOnce() {
        assertThatThrownBy(() -> new ElasticsearchProjectionFactory().open(definition(Map.of(
                "indexPrefix", "Niem_", "refresh", "sometimes", "timeoutSeconds", "-1", "user", "niem")),
                CONTEXT))
                .isInstanceOf(ProjectionDefinitionException.class)
                .satisfies(e -> assertThat(((ProjectionDefinitionException) e).problems())
                        .hasSize(5)
                        .anySatisfy(p -> assertThat(p).contains("'url'"))
                        .anySatisfy(p -> assertThat(p).contains("'indexPrefix'"))
                        .anySatisfy(p -> assertThat(p).contains("'refresh'"))
                        .anySatisfy(p -> assertThat(p).contains("'timeoutSeconds'"))
                        .anySatisfy(p -> assertThat(p).contains("'passwordEnv'")));
    }

    @Test
    void aNamedPasswordVariableThatIsNotSetIsRefused() {
        assertThatThrownBy(() -> new ElasticsearchProjectionFactory().open(definition(Map.of(
                "url", "http://localhost:1", "user", "niem", "passwordEnv", "NIEM_SEARCH_PASSWORD")),
                CONTEXT))
                .isInstanceOf(ProjectionDefinitionException.class)
                .hasMessageContaining("$NIEM_SEARCH_PASSWORD");
    }
}
