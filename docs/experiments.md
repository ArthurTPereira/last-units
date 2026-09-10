# Experiment log, phases 1 and 2

A record of what was predicted, what was run and what came out. The rule is that
the prediction is written before the run. Where no prediction was recorded, it
says so, because inventing one afterwards does not count.

Environment: PostgreSQL 17.11 in a container, `default_transaction_isolation =
read committed`, server in UTC. Two independent `psql` sessions, with distinct
PIDs.

> The lab sessions were run with Portuguese identifiers. The table name, the
> column names and the query aliases were translated here, along with the DDL
> that created them, so the document reads consistently. No measured value was
> touched: every number, error code and error message is what the session
> produced. The automated test reports were not translated but regenerated, by
> rerunning the suite.

---

## Exp 0: transaction behaviour (03 and 04/09/2026)

A lab table in the banking domain, chosen so it does not contaminate the modelling
of the real domain:

```sql
CREATE TABLE accounts (
    id      int PRIMARY KEY,
    holder text NOT NULL,
    balance   numeric(12,2) NOT NULL
);
INSERT INTO accounts VALUES (1, 'ana', 100.00), (2, 'bruno', 100.00);
```

| # | Question | Prediction | Result | Matched? |
|---|---|---|---|---|
| 1 | Is an `INSERT` without `BEGIN` permanent? | "Only after COMMIT, depends on autocommit" | It persisted. Confirmed afterwards by tearing the container down and bringing it back | yes, for an imprecise reason |
| 2 | With `BEGIN` + `UPDATE` and no commit, what does each session see? | S1 sees 999, S2 sees the original | Exactly that | yes |
| 3 | After `ROLLBACK` | Both see the original value | Exactly that | yes |
| 4 | `BEGIN`, ok update, error, ok update, `COMMIT` | "The ones that work persist, the failed one is ignored" | The whole transaction aborted. `COMMIT` returned `ROLLBACK` | no |
| 5 | Closing the terminal with an open transaction | "Nothing would be saved" | Nothing saved, but the transaction was still there | partly |
| 6 | State in `pg_stat_activity` | | `idle in transaction` | |

Output from item 6:

```
 136 | idle in transaction | 2026-09-04 01:30:12.512684+00 | UPDATE accounts SET balance = 999 where id = 1;
```

### Conclusions from Exp 0

Every command runs inside a transaction. Without `BEGIN`, it is a transaction of
a single command, opened and closed by the client. Autocommit is client behaviour
(psql, the JDBC driver), not server behaviour.

An error aborts the whole transaction, not just the statement. That is different
from Oracle, where the error only knocks out the command that failed. The
consequence is that, in a unit of work with several steps, a failure in the last
one discards everything before it.

"The application died" is not the same thing as "the transaction was rolled
back". There is an interval between the two events. The database does not know
the application crashed; it finds out later, on its own.

ACID does not protect a business rule. The "C" only covers constraints declared in
the database. The "D" guarantees the effect survives, not that the client got to
know it happened.

---

## Exp 1: lost update in Read Committed (04/09/2026)

The history reproduced:

```
r1[x=100] r2[x=100] w2[x=90] c2 w1[x=90] c1
```

Two withdrawals of 10 from a balance of 100. Each session reads the balance,
computes the new value outside the database, and writes an absolute value. That
is the read-modify-write pattern, which is how application code usually writes.

Level: `read committed`, the default, left untouched.

### Prediction

By the matrix, Read Committed allows lost update. The expectation was `balance =
90`, where the correct answer would be 80.

### Result

```
 balance | invariant_ok
---------+--------------
   90.00 | f
(1 row)
```

Twenty was withdrawn from an account of 100, and 90 was left.

### Conclusion

Confirmed. Neither transaction got an error and both committed successfully. The
anomaly is silent: there is no exception, no log entry about anything unusual,
and a balance of 90 is a perfectly valid value for the database, breaking no
type, no `NOT NULL` and no key.

The `invariant_ok` column only exists because we, from outside, knew the answer
should be 80. That is a test oracle, and production has no oracle. Nobody is
counting how many operations should have happened. The loss only shows up when
someone compares the system against the real world, through stocktaking, a
customer complaint or accounting close, days later and with no trace of which
operations vanished.

### Pending variant

The same history with different values, S1 withdrawing 10 and S2 withdrawing 30.
Not run yet. It would make the loss visible in the row itself. The equal-values
version is the one that shows the anomaly leaves no trace, and it is the closest
to the stock domain, where two buyers decrementing 1 unit produce exactly the
same value.

---

## Exp A: waiting does not save you (04/09/2026)

The same scenario as Exp 1, with one change: S2 does not commit before S1 writes.

```
r1[x=100] r2[x=100] w2[x=90] w1[x=90](blocks) c2 (w1 proceeds) c1
```

### Prediction

Not recorded before the run. A methodological slip, noted.

### Result

Session 1 blocked on the `UPDATE` and only proceeded after session 2 committed.

```
 balance | invariant_ok
---------+--------------
   90.00 | f
(1 row)
```

### Conclusion

There was waiting, the waiting worked, and the wrong result came out all the same.

What S1 waited for was the end of S2's transaction, not the commit specifically.
A `ROLLBACK` in S2 would have released S1 just the same, and in that case it
would have written 90 over 100, with no loss at all.

The waiting exists because dirty write is the one anomaly no isolation level
allows. Without forbidding it, `ROLLBACK` would have no defined meaning. It is
the column of the matrix with the same answer in all four rows.

The point of the experiment is the value written. Session 1's 90 was computed at
step 1, before S2 had read anything. It sat still in the application while the
state changed underneath it. When S1 finally wrote, it sent a stale conclusion
about a world that no longer existed. The waiting happened after the decision was
already made, and so it never had a chance to help.

> The wait protected the write. It did not protect the decision.

There is a window between the instant the application decides a value and the
instant it writes. In that window the state changes, and nothing warns you.

The database broke no promise: it prevented dirty write, which it promised, and
allowed lost update, which it never promised to prevent. The error is in how the
operation was written, assuming the world would stand still between the read and
the write.

---

## Exp B: the same history in Repeatable Read (04/09/2026)

The history is identical to Exp 1, replacing only the `BEGIN` in both sessions
with:

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
```

### Prediction

By the matrix, Repeatable Read prevents lost update, both in the standard and in
PostgreSQL. The expectation was that the anomaly would not occur.

### Result

At session 1's `UPDATE`, not at the `COMMIT`:

```
ERROR:  could not serialize access due to concurrent update
```

The following `COMMIT` returned `ROLLBACK`, and the final state was:

```
 balance | invariant_ok
---------+--------------
   90.00 | f
(1 row)
```

### Conclusion

The `f` here means something entirely different from the `f` in Exp 1.

In Exp 1, both withdrawals succeeded and the state ended up wrong: data
corrupted, silently. In Exp B, only one withdrawal succeeded, and the other was
refused with an explicit error. A balance of 90 is the correct result for a
single withdrawal of 10.

What failed in Exp B was the assertion, not the data. The constant `80.00`
assumes both operations went through, which is true in Exp 1 and false here.

The consequence for the phase 5 test is that the assertion cannot be a constant.
It has to relate the final state to the number of operations that actually
succeeded:

```
final_balance == initial_balance - (withdrawal * confirmed_operations)
```

A test that does not tell "operation refused" apart from "operation lost" fails
correct behaviour and passes incorrect behaviour.

On the abort, it is the same mechanics as Exp 0 item 4: an error aborts the whole
transaction in PostgreSQL, and the subsequent `COMMIT` turns into `ROLLBACK`. The
final 90 comes from session 2, and session 1 left nothing behind.

### What it costs

The higher level did not make the system more correct for free. It traded a
silent failure for a loud one. In Read Committed nobody is warned and the data
ends up wrong. In Repeatable Read the data ends up right, and somebody gets an
error that is neither a data error nor a network error, and that the application
has to know how to handle.

If the code does not handle `could not serialize access`, preventing the anomaly
turns into an error in the customer's face. Session 1's customer asked for a
withdrawal and did not get it, and somebody has to decide what happens to them.

Something to confirm in Exp C: here the error appeared at the `UPDATE`. Worth
checking where it appears under `serializable`, because the moment the
transaction dies changes which code has to deal with it.

## Exp C: write skew (04/09/2026)

The declared business rule: `sum(balance)` may never fall below 100.

Each session queries the sum before acting and withdraws 60 from a different
account.

```
r1[sum=200] r2[sum=200] w1[ana=40] c1 w2[bruno=40] c2
```

### Prediction

By the matrix, in PostgreSQL `repeatable read` allows write skew. That is the
point where the implementation is weaker than the SQL standard. `serializable`
prevents it.

### Result in `repeatable read`

```
 total | invariant_ok
-------+--------------
 80.00 | f
(1 row)
```

No error. No warning. Both transactions committed successfully.

### Result in `serializable`

At session 2's `UPDATE`:

```
ERROR:  could not serialize access due to read/write dependencies among transactions
DETAIL:  Reason code: Canceled on identification as a pivot, during write.
HINT:  The transaction might succeed if retried.
```

Final state:

```
 total  | invariant_ok
--------+--------------
 140.00 | t
(1 row)
```

### Conclusion

The same level that in Exp B aborted session 1 and protected the data did nothing
here. Raising the isolation level is not a staircase where each step covers one
more piece of the problem. Each level excludes a specific class of phenomenon,
and write skew does not belong to the class `repeatable read` excludes.

The reason is that the two transactions wrote to different rows. There is no
write conflict to detect. And the rule that was violated does not live in either
row: it lives in their sum, which is a relationship the database does not know
exists.

A trap I fell into while reading the result: I looked at the final state, saw the
arithmetic worked out (200 minus 60 minus 60 is 80) and concluded it was fine.
That was exactly the reasoning of both transactions. Each one checked the state,
saw its own arithmetic worked out, and acted. No single operation is wrong. The
error is in the combination.

The difference from Exp 1 is that there `80.00` was a test oracle, a number that
only existed outside the system. Here `>= 100` is a declared business rule, the
direct analogue of "never sell more units than exist".

### On the abort in `serializable`

The `Reason code` ends in `during write`, meaning the cancellation came at the
`UPDATE` and not at the `COMMIT`, just like Exp B. Under `serializable` the
cancellation can also happen at the commit, and retry code has to cover both
points.

The `HINT` is a contract, not a courtesy. The transaction was cancelled because
of a conflict, not because of invalid data, and trying again is the expected
answer. Whoever writes the retry is the application.

---

## Exp D: automated concurrency test, naive V1 (04/09/2026)

The first experiment outside psql. Spring Boot 4.1.1, Java 25, JPA and Postgres
17 through Testcontainers.

The scenario: 3 units in stock, 200 clients each firing a purchase of 1 unit,
with a synchronized start using `CountDownLatch` and virtual threads.

The service is deliberately naive: it reads the stock, decides how much to
fulfil, deducts it and records the order. `@Transactional` at the database
default, `read committed`, with no protection at all.

### Prediction

It fails. Lost update, the same as Exp 1, now at scale and with nobody
orchestrating the order of events.

### Result

```
===================================================
V1 - NAIVE, NO PROTECTION
===================================================
units in stock at start ......... 3
concurrent requests ............. 200
---------------------------------------------------
purchases fulfilled ............. 13
purchases declined (no stock) ... 187
aborted (wait limit) ............ 0
unexpected exceptions ........... 0
---------------------------------------------------
units sold (sum) ................ 13
final stock ..................... 0
---------------------------------------------------
no oversell ..................... false
counter not negative ............ true
counter consistent .............. false
===================================================
```

Thirteen units sold from a stock of 3. Zero exceptions. The count varies between
runs, from 11 to 32 across the measurements taken, and that instability is part
of what makes the bug hard to notice.

### Conclusions

The counter never gives it away. Final stock is 0 and the non-negative check
reads true in every run. Each thread only decrements if it read at least 1, and
writes the absolute value `read - 1`, so the floor is always 0. A human looking
at the `produto` table reads "sold out, all good". The violation only shows up
when you compare the counter against the recorded orders.

If the test had a single assertion, looking at the counter, it would pass.

The failure is stable, not a rare case. With 200 concurrent requests it happens
in every run, always several times the real stock. It is not a race
that shows up once in a thousand.

There was also a trap in the test itself. Its first version went green when the
service was not implemented: every request blew up, nobody bought anything, and
the three invariants passed. Two sanity assertions had to be added, one requiring
no unexpected exception and another requiring every request to produce an
outcome. A concurrency test that does not prove there was concurrency gives a
false green.

### Exp D.1: the ORM changed the result

A controlled comparison, three runs each, without changing a line of business
logic. The only difference is `save` versus `saveAndFlush` when recording the
order.

| Version | Run 1 | Run 2 | Run 3 | Range |
|---|---|---|---|---|
| `save`, flush at commit | 32 | 30 | 32 | 30 to 32 |
| `saveAndFlush`, immediate flush | 28 | 28 | 26 | 26 to 28 |

The ranges do not overlap.

The explanation is that `flush` acts on the whole persistence context, not just
on the entity passed to it. `saveAndFlush(order)` pushes along the product
`UPDATE` that dirty checking detected. Without it, that `UPDATE` only reaches the
database at commit, and the window in which other transactions read the old value
gets wider.

The consequence is that the moment a command reaches the database is decided by
the ORM, not by the business code. Whoever writes `product.setAvailableQuantity(...)`
is not writing to the database; they are recording an intention that Hibernate
dispatches whenever it feels like it. That is the concrete argument for the JPA
versus explicit SQL discussion.

One run was discarded from the comparison: a result of 25 obtained before fixing
a deduction bug, which subtracted the requested quantity instead of the fulfilled
one.

---

### Exp D.2: measuring latency, and the pool that made it worse

The test was instrumented with p50, p95, p99 and max per request, plus the batch
duration. 200 clients, 3 units, naive V1 with no protection.

Connection pool at the HikariCP default of 10:

| | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| p50 | 364 ms | 316 ms | 305 ms |
| p95 | 497 ms | 449 ms | 402 ms |
| p99 | 500 ms | 452 ms | 411 ms |
| whole batch | 525 ms | 475 ms | 434 ms |

The hypothesis under test was that latency would be dominated by the connection
pool queue. If that were true, widening the pool would reduce latency.

With a pool of 50:

| | Run 1 | Run 2 |
|---|---|---|
| p50 | 442 ms | 464 ms |
| p99 | 595 ms | 632 ms |
| whole batch | 618 ms | 665 ms |

Hypothesis refuted. Widening the pool made things worse.

The bottleneck is contention on the product row, not the pool. With 10
connections, only 10 requests fight over the same row at a time, and the pool was
acting as a concurrency limiter without anyone having designed it that way. With
50, fifty fight, the wait on the row grows and the overhead comes along with it.

On a hot resource, widening the pool can degrade things. Direct material for the
scaling strategy document.

A note on method: 2 runs against 3, same machine, Docker in between. The
direction is consistent; the magnitude is not precise.

A note on scope: measured on V1, with no protection at all. V2 adds queue
waiting, so the measurement has to be redone and the values revised.

---

## Exp E: `SELECT ... FOR UPDATE` (09/09/2026)

Four scripts in psql, on the `accounts` table, to produce evidence of my own for a
mechanism that until then sat as "no evidence" in the phase 3 table.

### E.A: where the waiting happens

S1 opens a transaction and runs `SELECT ... FOR UPDATE`. S2 tries the same and
blocks. S1 withdraws 10 and commits. S2 is released and prints 90, not 100. It
withdraws 10 from what it read. Final balance 80, correct.

The comparison with Exp A is the finding. In both cases there was waiting. In Exp
A the result came out wrong; here it came out right. The difference is not
whether waiting happens. It is where it happens in the sequence:

| | Exp A (lost update) | Exp E.A (`FOR UPDATE`) |
|---|---|---|
| Waiting happens | after the decision | at the read, before the decision |
| Value written | computed from stale data | computed from fresh data |
| Result | 90 (wrong) | 80 (correct) |

The sentence from Exp A was "the wait protected the write, not the decision".
Here the wait happens before there is any decision to protect.

### E.B: `NOWAIT`, failing instead of waiting

```
BEGIN;
SELECT balance FROM accounts WHERE id = 1 FOR UPDATE NOWAIT;
BEGIN
ERROR:  could not obtain lock on row in relation "accounts"
```

### E.C: `lock_timeout`, testing D5

```
SET lock_timeout = '1s';
BEGIN;
SELECT balance FROM accounts WHERE id = 1 FOR UPDATE;
SET
BEGIN
ERROR:  canceling statement due to lock timeout
CONTEXT:  while locking tuple (0,1) in relation "accounts"
```

It held for 1 second and failed, as configured. The `CONTEXT` confirms the
cancellation happened while acquiring the row lock.

Pending: run the same thing with `statement_timeout` to close the gap in D5.

### E.D: deadlock

Two sessions acquiring two rows in opposite orders. S1 locks id 1, S2 locks id 2,
S1 asks for id 2, S2 asks for id 1.

The first run was discarded because the `lock_timeout = '1s'` from E.C was still
active, and the PostgreSQL deadlock detector also fires in the 1 second range.
There was no way to tell which of the two killed the transaction. Repeated after
`RESET lock_timeout`.

```
ERROR:  deadlock detected
DETAIL:  Process 78 waits for ShareLock on transaction 798; blocked by process 55.
Process 55 waits for ShareLock on transaction 799; blocked by process 78.
HINT:  See server log for query details.
CONTEXT:  while locking tuple (0,1) in relation "accounts"
```

The `DETAIL` spells out the whole cycle. Which one dies is the database's choice,
and here it was process 78.

A detail worth keeping: compare the `HINT` lines. In the serialization error from
Exp C, Postgres says "The transaction might succeed if retried". Here it suggests
nothing of the sort. Deadlock is not congestion to be retried. It is a symptom of
divergent acquisition order, and the fix is a design fix, not a retry.

### Consequence

D6 was refined with a third clause. See
[product-decisions.md](product-decisions.md).

---

## Exp F: V2 with `FOR UPDATE`, and a load sweep (09/09/2026)

### F.1: the red test goes green

The same scenario as Exp D, with 3 units and 200 simultaneous requests for 1 unit
each. The only change in the code is that the product read became
`SELECT ... FOR UPDATE`, through `@Lock(PESSIMISTIC_WRITE)`.

```
===================================================
V2 - WITH PESSIMISTIC LOCKING
===================================================
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
===================================================
```

Three runs, identical result: exactly 3 sold. The other 197 got a business
refusal, not an error.

### F.2: the cost, measured

| | V1 (broken) | V2 (correct) | Cost |
|---|---|---|---|
| p50 | 305 to 364 ms | 504 to 560 ms | about 1.6x |
| p95 | 402 to 497 ms | 740 to 803 ms | about 1.7x |
| max | about 500 ms | 759 to 823 ms | about 1.6x |

The prediction written in the technical decision before running, that the lock
would last a conversation rather than a command, is confirmed with numbers.

### F.3: load sweep, the invariant does not break

| Clients | Pool | Fulfilled | Oversell | Counter consistent | p95 |
|---|---|---|---|---|---|
| 250 | 10 | 3 | no | yes | 882 ms |
| 300 | 10 | 3 | no | yes | 977 ms |
| 400 | 10 | 3 | no | yes | 1196 ms |
| 600 | 10 | 3 | no | yes | 1516 ms |
| 400 | 50 | 3 | no | yes | 1484 ms |
| 400 | 100 | 3 | no | yes | 1494 ms |

Always 3. Tripling the load and multiplying the pool tenfold did not move the
correction. What degrades under load is latency, not the invariant.

### F.4: where the `lock_timeout` was

Zero aborts across the whole sweep, including with 600 clients and a maximum
latency of 1570 ms, well above the 1 s `lock_timeout`. That did not add up.

An indirect measurement of the wait on the row, lowering the limit until it
fired, with 400 clients and a pool of 100:

| `lock_timeout` | Declined | Aborted |
|---|---|---|
| 1 s | 397 | 0 |
| 200 ms | 150 | 247 |
| 50 ms | 43 | 354 |
| 10 ms | 3 | 394 |

The real wait on the row sits between 200 ms and 1 s. All the rest of the
latency, which is most of it, is queueing for a connection from the pool plus
overhead, outside the database.

Two conclusions follow. The first is that D5 is calibrated right, for the right
reason: the 1 s `lock_timeout` was set to mean "this is not congestion, this is
something stuck", and normal congestion, even with 600 concurrent requests, does
not reach it. The second is that the 3 s UX budget is unprotected, because
neither `lock_timeout` nor `statement_timeout` can see the pool queue, which
happens before any command reaches the database. HikariCP has a
`connection-timeout` that defaults to 30 seconds.

### F.5: the test's second false green

On the `lock_timeout = 10ms` row, 394 of 400 requests were aborted and the test
passed.

The invariant was intact, so the assertions approved it. But a system that
technically refuses 98% of its customers meets neither D6 nor D5.

It is the same pattern as the first false green: the test validates correctness
and is blind to the service promise. Correctness and quality of service are
different invariants and need different assertions.

---

## Phase 2 balance

What was proven with output on screen, not from reading:

1. Lost update happens in `read committed`, silently. No error, no log entry, and
   a final state that is plausible and indistinguishable from a correct run.
2. Waiting does not save you. The wait protects the write, not the decision made
   before it.
3. `repeatable read` prevents lost update by aborting one of the transactions,
   with an explicit error the application has to handle.
4. `repeatable read` does not prevent write skew. `serializable` does, also by
   aborting, and it signals a retry.
5. The assertion in a concurrency test cannot be a constant. It has to relate the
   final state to the number of confirmed operations.
6. A rule that lives in the relationship between rows, rather than inside one
   row, is invisible to the database until it is expressed in a way the database
   understands.

Not run yet: the Exp 1 variant with different withdrawal amounts.
