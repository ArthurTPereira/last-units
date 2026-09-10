package dev.arthur.stock;

import dev.arthur.stock.purchase.PurchaseResult;
import dev.arthur.stock.purchase.PurchaseService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

// The scenario shared by both proofs: N customers competing for a product that has fewer units than the demand.
abstract class ConcurrencyScenario {

    protected static final int INITIAL_UNITS = 3;

    // Adjustable: -Dtest.clients=400
    protected static final int CLIENTS = Integer.getInteger("test.clients", 200);

    protected static final int QUANTITY_PER_CLIENT = 1;

    private static final String SQLSTATE_QUERY_CANCELED = "57014";
    private static final String SQLSTATE_LOCK_NOT_AVAILABLE = "55P03";

    @Autowired
    protected PurchaseService purchaseService;

    @Autowired
    protected JdbcTemplate jdbc;

    protected long productId;
    private List<Long> customerIds;

    @BeforeEach
    void prepareScenario() {
        jdbc.execute("TRUNCATE orders, product, customer RESTART IDENTITY CASCADE");

        productId = jdbc.queryForObject(
                "INSERT INTO product (sku, name, available_quantity) VALUES (?, ?, ?) RETURNING id",
                Long.class, "SKU-1", "Contested product", INITIAL_UNITS);

        customerIds = jdbc.queryForList(
                "INSERT INTO customer (name) SELECT 'customer-' || g FROM generate_series(1, ?) g RETURNING id",
                Long.class, CLIENTS);
    }

    protected record Outcome(
            int fulfilled,
            int declined,
            int aborted,
            List<Throwable> failures,
            int finalStock,
            long unitsSold) {

        boolean noOversell() {
            return unitsSold <= INITIAL_UNITS;
        }

        boolean counterNotNegative() {
            return finalStock >= 0;
        }

        boolean counterConsistent() {
            return finalStock == INITIAL_UNITS - (int) unitsSold;
        }
    }

    protected Outcome firePurchases(String title) throws Exception {
        var ready = new CountDownLatch(CLIENTS);
        var start = new CountDownLatch(1);
        var fulfilled = new AtomicInteger();
        var declined = new AtomicInteger();
        var aborted = new AtomicInteger();
        var failures = new CopyOnWriteArrayList<Throwable>();
        var durationsNanos = new CopyOnWriteArrayList<Long>();

        long batchStart;
        long batchEnd;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long customerId : customerIds) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        long t0 = System.nanoTime();
                        PurchaseResult result =
                                purchaseService.purchase(customerId, productId, QUANTITY_PER_CLIENT);
                        durationsNanos.add(System.nanoTime() - t0);
                        if (result.declined()) {
                            declined.incrementAndGet();
                        } else {
                            fulfilled.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        if (isWaitLimit(t)) {
                            aborted.incrementAndGet();
                        } else {
                            failures.add(t);
                        }
                    }
                });
            }
            ready.await(30, TimeUnit.SECONDS);
            batchStart = System.nanoTime();
            start.countDown();
        }
        batchEnd = System.nanoTime();

        int finalStock = jdbc.queryForObject(
                "SELECT available_quantity FROM product WHERE id = ?", Integer.class, productId);
        long unitsSold = jdbc.queryForObject(
                "SELECT coalesce(sum(fulfilled_quantity), 0) FROM orders WHERE product_id = ?",
                Long.class, productId);

        var outcome = new Outcome(fulfilled.get(), declined.get(), aborted.get(),
                failures, finalStock, unitsSold);

        printReport(title, outcome, durationsNanos, batchEnd - batchStart);
        return outcome;
    }

    private static boolean isWaitLimit(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (SQLSTATE_QUERY_CANCELED.equals(state)
                        || SQLSTATE_LOCK_NOT_AVAILABLE.equals(state)) {
                    return true;
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private void printReport(String title, Outcome o, List<Long> durationsNanos, long batchNanos) {

        var sorted = new ArrayList<Long>(durationsNanos);
        sorted.sort(null);

        String lockTimeout = jdbc.queryForObject("SHOW lock_timeout", String.class);
        String statementTimeout = jdbc.queryForObject("SHOW statement_timeout", String.class);

        String report = """

                ===================================================
                %s
                ===================================================
                units in stock at start ......... %d
                concurrent requests ............. %d
                ---------------------------------------------------
                purchases fulfilled ............. %d
                purchases declined (no stock) ... %d
                aborted (wait limit) ............ %d
                unexpected exceptions ........... %d
                ---------------------------------------------------
                units sold (sum) ................ %d
                final stock ..................... %d
                ---------------------------------------------------
                no oversell ..................... %s
                counter not negative ............ %s
                counter consistent .............. %s
                ------------------ LATENCY ------------------------
                samples ......................... %d
                p50 ............................. %.1f ms
                p95 ............................. %.1f ms
                p99 ............................. %.1f ms
                max ............................. %.1f ms
                whole batch ..................... %.1f ms
                --------------- CONFIGURATION ---------------------
                lock_timeout .................... %s
                statement_timeout ............... %s
                ===================================================

                """;

        System.out.printf(report,
                title,
                INITIAL_UNITS, CLIENTS,
                o.fulfilled(), o.declined(), o.aborted(), o.failures().size(),
                o.unitsSold(), o.finalStock(),
                o.noOversell(), o.counterNotNegative(), o.counterConsistent(),
                sorted.size(),
                ms(percentile(sorted, 50)),
                ms(percentile(sorted, 95)),
                ms(percentile(sorted, 99)),
                ms(sorted.isEmpty() ? 0L : sorted.get(sorted.size() - 1)),
                ms(batchNanos),
                lockTimeout, statementTimeout);

        o.failures().stream().map(Throwable::toString).distinct().limit(3)
                .forEach(m -> System.out.println("exception: " + m));
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
