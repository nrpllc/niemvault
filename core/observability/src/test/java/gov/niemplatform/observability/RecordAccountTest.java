package gov.niemplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The books for a run.
 *
 * <p>The failure this exists to catch is the one nothing else can see: a record that lands and then
 * stops existing. §4.2 requires bad data not to halt the pipeline, so quarantining is routine — and
 * that makes a short count indistinguishable from a normal run over a messy feed unless something
 * has to balance.
 */
@DisplayName("A record account")
class RecordAccountTest {

    private static final PipelineContext CONTEXT = PipelineContext.of("riverton-pd-cad", "run-1");

    /** Ten records through three hops, everything mapped. The shape of the shipped fixture. */
    private static RecordAccount cleanRun() {
        RecordAccount account = new RecordAccount("bronze-to-silver", 3);
        for (int record = 0; record < 10; record++) {
            account.landed();
            account.accountFor(3, 0, 0);
        }
        return account;
    }

    @Nested
    @DisplayName("that balances")
    class Balances {

        @Test
        @DisplayName("reconciles a clean run")
        void cleanRunBalances() {
            RecordAccount account = cleanRun();

            assertThat(account.expected()).isEqualTo(30);
            assertThat(account.accounted()).isEqualTo(30);
            assertThat(account.residual()).isZero();
            assertThat(account.balances()).isTrue();
        }

        @Test
        @DisplayName("counts a quarantined hop as accounted for, because it is")
        void quarantineIsNotLoss() {
            // The platform knows exactly where a quarantined record went and can produce it. That
            // is the difference between a rejection and a loss, and the whole reason the two must
            // not be conflated: a run over a messy feed is not a broken run.
            RecordAccount account = new RecordAccount("bronze-to-silver", 3);
            account.landed();
            account.accountFor(2, 1, 0);

            assertThat(account.balances()).isTrue();
            assertThat(account.quarantinedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("counts a hop skipped for a failed dependency as accounted for")
        void skipIsNotLoss() {
            // One bad hop costs that hop and its dependents. The association hop cannot run when
            // the person hop failed, and that is a known outcome rather than a missing one.
            RecordAccount account = new RecordAccount("bronze-to-silver", 3);
            account.landed();
            account.accountFor(1, 1, 1);

            assertThat(account.balances()).isTrue();
            assertThat(account.skippedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("emits nothing when the books balance")
        void noEventOnABalancedRun() {
            // An ERROR saying nothing is wrong is how an event stream stops being read.
            assertThat(cleanRun().breach(CONTEXT)).isEmpty();
        }
    }

    @Nested
    @DisplayName("that does not balance")
    class Breaches {

        @Test
        @DisplayName("catches a hop that produced nothing and said nothing")
        void catchesASilentlyDroppedHop() {
            // The case no other event covers. The hop did not fail a contract, so there is no
            // violation; it was not skipped, so nothing recorded a reason. It simply returned with
            // nothing, and every count in the run summary still looks plausible.
            RecordAccount account = new RecordAccount("bronze-to-silver", 3);
            account.landed();
            account.accountFor(2, 0, 0);

            assertThat(account.balances()).isFalse();
            assertThat(account.residual()).isEqualTo(-1);
            assertThat(account.breach(CONTEXT)).isPresent();
        }

        @Test
        @DisplayName("catches a record that landed and was never processed at all")
        void catchesAnUnprocessedRecord() {
            RecordAccount account = new RecordAccount("bronze-to-silver", 3);
            account.landed();
            account.landed();
            account.accountFor(3, 0, 0);

            assertThat(account.residual()).isEqualTo(-3);
            assertThat(account.breach(CONTEXT).orElseThrow().isLoss()).isTrue();
        }

        @Test
        @DisplayName("catches duplication as readily as loss")
        void catchesDuplication() {
            // More out than in is the same arithmetic failing the other way, and just as wrong:
            // a duplicated canonical record is a person who appears twice in the graph.
            RecordAccount account = new RecordAccount("bronze-to-silver", 3);
            account.landed();
            account.accountFor(4, 0, 0);

            assertThat(account.residual()).isEqualTo(1);
            assertThat(account.breach(CONTEXT).orElseThrow().isLoss()).isFalse();
        }

        @Test
        @DisplayName("reports the breach at ERROR, with the residual and the breakdown")
        void breachCarriesTheNumbers() {
            RecordAccount account = new RecordAccount("bronze-to-silver", 3);
            account.landed();
            account.accountFor(1, 0, 0);

            CompletenessBreach breach = account.breach(CONTEXT).orElseThrow();

            assertThat(breach.severity()).isEqualTo(Severity.ERROR);
            assertThat(breach.type()).isEqualTo(EventType.COMPLETENESS_BREACH);
            assertThat(breach.attributes())
                    .containsEntry("expected", 3L)
                    .containsEntry("accounted", 1L)
                    .containsEntry("residual", -2L)
                    .containsEntry("stage", "bronze-to-silver");
            assertThat(breach.summary()).contains("does not balance", "-2");
        }
    }

    @Nested
    @DisplayName("as an event")
    class AsAnEvent {

        @Test
        @DisplayName("cannot be constructed for a run that balanced")
        void refusesABalancedBreach() {
            // Guarded at the constructor rather than left to callers. An event type that can say
            // "nothing is wrong" will eventually be emitted saying it.
            assertThatThrownBy(() -> new CompletenessBreach(CONTEXT, "bronze-to-silver", 30, 30, 30, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requires an imbalance");
        }

        @Test
        @DisplayName("carries no record value, per ADR 0015")
        void carriesNoValues() {
            // An account counts; it never sees a value. Asserted because the obligation is
            // inherited by every new type and the compiler does not enforce it.
            RecordAccount account = new RecordAccount("bronze-to-silver", 3);
            account.landed();
            account.accountFor(1, 0, 0);

            CompletenessBreach breach = account.breach(CONTEXT).orElseThrow();

            assertThat(breach.attributes().values())
                    .allSatisfy(value -> assertThat(value).isNotInstanceOf(Object[].class));
            assertThat(account.toString()).doesNotContain("DOE", "JANE");
        }
    }

    @Test
    @DisplayName("refuses a mapping with no hops, which could never balance meaningfully")
    void refusesZeroHops() {
        assertThatThrownBy(() -> new RecordAccount("bronze-to-silver", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one hop");
    }
}
