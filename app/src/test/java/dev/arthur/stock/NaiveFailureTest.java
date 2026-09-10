package dev.arthur.stock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

// The proof that V1 is broken is in the test. Reproductible in any environment, with one command. 
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "stock.purchase.mode=naive")
class NaiveFailureTest extends ConcurrencyScenario {

    @Test
    @DisplayName("V1: sells more than exists, silently")
    void sellsMoreThanExists() throws Exception {
        Outcome outcome = firePurchases("V1 - NAIVE, NO PROTECTION");

        assertThat(outcome.failures())
                .as("the anomaly produces no exception. If there is one, it is another problem")
                .isEmpty();

        assertThat(outcome.fulfilled() + outcome.declined() + outcome.aborted())
                .as("every request fired must have produced an outcome")
                .isEqualTo(CLIENTS);

        // Failure asserted
        assertThat(outcome.unitsSold())
                .as("V1 has to sell MORE than the %d units that existed. "
                        + "If it did not, either someone fixed NaivePurchase (revert it), "
                        + "or the machine did not generate enough concurrency "
                        + "(raise -Dtest.clients)", INITIAL_UNITS)
                .isGreaterThan(INITIAL_UNITS);

        assertThat(outcome.counterConsistent())
                .as("in V1 the counter does NOT match the sum of orders: "
                        + "fulfilled purchases vanish from the counter")
                .isFalse();

        // This part is what makes the anomaly really dangerous
        // The counter never goes below zero. Each request only decrements if it read at least 1, and writes  `read - 1`. 
        // Anyone looking at the product table after the damage reads "sold out, all good".
        assertThat(outcome.counterNotNegative())
                .as("the counter ends on a plausible value. The violation only shows up "
                        + "when you compare it against the recorded orders")
                .isTrue();
    }
}
