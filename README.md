# Last units

A purchase service with limited stock, built around a rule that cannot be broken:

**At no instant may the total committed units of a product exceed the total units that exist.**

This project doesn't have a UI. Its purpose is to prove that the system solves the above problem: you'll see the failure happen, and then see it resolved with the actual output.

---
## The proof

You need Docker running, since the tests start a real Postgres container, and a JDK 25.

```bash
cd app
./mvnw test
```

On Windows, `.\mvnw.cmd test`.

Run one command and you'll get two passing tests. The first one shows the bug actually happens, and the second one shows it's fixed. Under the hood, they both spin up a fresh Postgres container via Testcontainers, stock 3 items, and hit the system with 200 simultaneous buy requests at the exact same time.

The only difference between them is one line of code: whether the product read holds the row.

### `NaiveFailureTest`, V1 with no protection

```
units in stock at start ......... 3
concurrent requests ............. 200
---------------------------------------------------
purchases fulfilled ............. 11
purchases declined (no stock) ... 189
aborted (wait limit) ............ 0
unexpected exceptions ........... 0
---------------------------------------------------
units sold (sum) ................ 11
final stock ..................... 0
---------------------------------------------------
no oversell ..................... false
counter not negative ............ true
counter consistent .............. false
```

Eleven units sold from a stock of 3, with no exception and no error in any log.

This test passes when the invariant fails. We aren't just guessing that the bug happened and got fixed, we are proving it. It reproduces the failure on every build, fluctuating between 11 and 32 units sold from a stock of 3, according to my measurements. The instability is part of what makes the bug hard to notice in production.


### `ConcurrentPurchaseTest`, V2 with pessimistic locking

```
units in stock at start ......... 3
concurrent requests ............. 200
---------------------------------------------------
purchases fulfilled ............. 3
purchases declined (no stock) ... 197
aborted (wait limit) ............ 0
unexpected exceptions ........... 0
---------------------------------------------------
units sold (sum) ................ 3
final stock ..................... 0
---------------------------------------------------
no oversell ..................... true
counter not negative ............ true
counter consistent .............. true
```

Exactly 3, which is the stock that existed. The other 197 got a business refusal, not a technical error.

The [naive version](app/src/main/java/dev/arthur/stock/purchase/NaivePurchase.java) is kept alive in the code, next to the
[safe one](app/src/main/java/dev/arthur/stock/purchase/SafePurchase.java), and selected through `stock.purchase.mode`. It is there to demonstrate and replicate the bug.

### The detail that matters more than the number

In V1, `counter not negative` came out `true`, and stock ended at 0.

The counter never goes below zero, because each request only decrements if it read at least 1, and writes the absolute value `read - 1`. Anyone looking at the product table after the damage reads "sold out, all good".

The violation only appears when you compare the counter against the recorded orders. That is why the test has several assertions rather than one. With a single one, looking at the stock, it would pass.

---

## Why the fix works

Not because it locks. The clearest evidence came from the manual `psql` experiments, on a smaller scene: an account holding 100, two sessions each withdrawing 10, so the only correct ending is 80. Both experiments below produce waiting, and give opposite results:

| | Naive V1 | V2 with `FOR UPDATE` |
|---|---|---|
| Where the waiting happens | after the decision | at the read, before the decision |
| Value written | computed from stale data | computed from fresh data |
| Balance left | 90, wrong | 80, correct |

With V1, the transaction paused, woke back up, and blindly wrote the stale data it computed beforehand. The locking mechanism saved the write operation, but it completely missed the decision-making logic.

With `SELECT ... FOR UPDATE` the wait happens at the read. When the transaction wakes up, it has not decided anything yet, and reads the state as it ended up. It is the same wait but in a different position in the sequence.

---

## What the fix cost

Same scenario on both sides: 200 concurrent clients, 3 units in stock, connection pool of 10.

| | V1 | V2 | Cost |
|---|---|---|---|
| p50 | 305 to 364 ms | 504 to 560 ms | about 1.6x |
| p95 | 402 to 497 ms | 740 to 803 ms | about 1.7x |

We wrote the prediction down before we even started: this lock covers the whole flow, not just one query. Think about everything happening between the initial `SELECT` and the final `COMMIT`. We have the network, the app logic, both writes. They are all extending how long the lock is held.

---

## Where it degrades

A load sweep, with stock always at 3 units:

| Clients | Pool | Fulfilled | Oversell | p95 |
|---|---|---|---|---|
| 250 | 10 | 3 | no | 882 ms |
| 300 | 10 | 3 | no | 977 ms |
| 400 | 10 | 3 | no | 1196 ms |
| 600 | 10 | 3 | no | 1516 ms |
| 400 | 100 | 3 | no | 1494 ms |

Always 3. Tripling the load and scaling up the connection pool didn't change the outcome. What degrades under load is latency, not the invariant.

### Two interesting findings

Widening the connection pool actually made things worse. In a separate run, outside the sweep above, going from 10 to 50 connections pushed p50 up from 305-364 ms to 442-464 ms. The real bottleneck is row contention, not the connection queue: with 10 connections only 10 requests fight over the same row at a time, and the pool was acting as a concurrency limiter nobody had designed.

Relying on database timeouts doesn't work for response budgets. Even with 1570 ms latencies under heavy load, the 1-second `lock_timeout` stayed silent. The actual row lock only takes 200ms to 1s. The extra time is spent waiting in the connection queue before hitting the database.

| `lock_timeout` | Declined | Aborted |
|---|---|---|
| 1 s | 397 | 0 |
| 200 ms | 150 | 247 |
| 50 ms | 43 | 354 |
| 10 ms | 3 | 394 |

---
## How the problem was investigated

Before any code, the anomalies were reproduced by hand, in two `psql` sessions side by side. The full record is in [docs/experiments.md](docs/experiments.md).

| Experiment | What it proved |
|---|---|
| Basic transaction | an error aborts the whole transaction; the application dying is not the same as the transaction being rolled back |
| Lost update in `read committed` | a silent anomaly: no error, no log, a plausible final state |
| The wait | waiting does not save you. The wait protects the write, not the decision made before it |
| `repeatable read` | prevents lost update by aborting a transaction, with an error the application has to handle |
| Write skew | `repeatable read` does not prevent it; `serializable` does, by aborting, and signals a retry |
| `FOR UPDATE` | waiting at the read produces a decision on fresh data; `NOWAIT`, `lock_timeout` and deadlock reproduced |

The raw working notes behind all of this, including the ANSI SQL anomaly matrix, are in [notes/](notes/).

---

## Decisions

The full record is in [docs/product-decisions.md](docs/product-decisions.md) and [docs/technical-decision.md](docs/technical-decision.md).

| | Decision | Cost accepted |
|---|---|---|
| D1 | No overselling. An unacceptable violation, not a repairable incident | refusing at checkout instead of selling and refunding |
| D2 | Partial fulfilment is valid, with no minimum | asked for 5, 3 exist: sell 3 and say so |
| D3 | Commit stock, then charge | units are held before any money exists |
| D4 | The cart does not reserve | the contest is settled when the order is confirmed |
| D5 | `lock_timeout` 1 s, total budget 3 s | derived from measurement, revised after V2 |
| D6 | Nobody fails from routine conflict; everyone waits | higher latency and a serialization point |

The chosen mechanism is pessimistic row locking (`SELECT ... FOR UPDATE`), out of a funnel of 7 candidates. The eliminations are split between those made on a structural property and those made on a product decision. The optimistic version and `SERIALIZABLE` are technically adequate for the problem and were discarded not because they are unable to solve it, but by a behavioural choice: [docs/phase-3-mechanisms.md](docs/phase-3-mechanisms.md).

---
## Stack

Java 25, Spring Boot 4.1.1, Spring Data JPA, PostgreSQL 17, Flyway, Testcontainers, JUnit 5 and Docker Compose.

--- 
## How this was built

The decisions here are all mine, including the invariant, product rules, the mechanism and the trade-offs accepted. I worked with an AI assistant as an adversary and reviewer, which argued against my choices, wrote the tests and the infrastructure, and helped reproduce the anomalies by hand in psql. Every experiment had a written prediction before it ran, and the predictions are recorded, including the ones that turned out wrong.

---
## License

MIT, see [LICENSE](LICENSE).
