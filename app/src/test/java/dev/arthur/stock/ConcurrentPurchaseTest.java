package dev.arthur.stock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

// The proof that V2 is correct is in the test.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "stock.purchase.mode=safe")
class ConcurrentPurchaseTest extends ConcurrencyScenario {

    @Test
    @DisplayName("V2: units sold never exceed the stock that existed")
    void cannotSellMoreThanExists() throws Exception {
        Outcome outcome = firePurchases("V2 - WITH PESSIMISTIC LOCKING");

        // These two exists because the test goes green when nothing happened
        // if every request blows up, nobody buys, the stock stays intact and the invariants "pass"
        assertThat(outcome.failures())
                .as("no request may fail with an unexpected error "
                        + "(the wait limit is counted separately)")
                .isEmpty();

        assertThat(outcome.fulfilled() + outcome.declined() + outcome.aborted())
                .as("every request fired must have produced an outcome")
                .isEqualTo(CLIENTS);

        assertThat(outcome.fulfilled())
                .as("someone has to have bought something, otherwise the test proved nothing")
                .isGreaterThan(0);

        // The core invariant being tested
        assertThat(outcome.unitsSold())
                .as("units sold cannot exceed the %d that existed", INITIAL_UNITS)
                .isLessThanOrEqualTo(INITIAL_UNITS);

        assertThat(outcome.finalStock())
                .as("the stock counter can never go negative")
                .isGreaterThanOrEqualTo(0);

        assertThat(outcome.finalStock())
                .as("final stock must equal the initial minus what was sold "
                        + "(if it does not, fulfilled purchases vanished from the counter)")
                .isEqualTo(INITIAL_UNITS - (int) outcome.unitsSold());
    }
}
