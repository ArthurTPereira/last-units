# Phase 3: available mechanisms

A survey without a recommendation. The choice and the bridge to the project's own
domain have not been made yet. The examples use the banking domain from the lab.

| Option | Excludes | How it fails | High contention on one item | Requires from the code | Where it does not help |
|---|---|---|---|---|---|
| Relative expression in the `UPDATE` | lost update in read-modify-write | waiting | queue; linear latency | the operation as a function of the current value | a decision based on an earlier read; a rule across rows |
| `SELECT ... FOR UPDATE` | lost update | waiting, or an error with `NOWAIT` | queue; risk of deadlock | a transaction covering read and write; fixed acquisition order | a row that does not exist; a rule across rows |
| Optimistic version (compare-and-set) | lost update, by detection | silence: 0 rows affected | no queue; cascading retries, starvation | checking the row count and retrying | high contention; write skew |
| `SERIALIZABLE` + retry | all of them, write skew included | serialization error | the abort rate climbs | retry on every write path | a transaction with a non-repeatable external effect |
| Declarative constraint | nothing about concurrency; turns a violation into an error | constraint error | irrelevant | a rule expressible on the row or as uniqueness | a rule in an aggregation across rows |
| Advisory lock | whatever you define | waiting, or `false` on the `try` variant | queue per key | a key convention; total discipline | nothing is protected if some path skips acquiring |
| `LOCK TABLE` | everything | waiting | serializes the table | little | almost always |

## Notes

Relative expression, `SET balance = balance - 10`. The read happens inside the
command, with no window between deciding and writing. In `read committed`, when
the command waits and is released, the `WHERE` is re-evaluated against the new
version; if it no longer holds, the row is skipped. In `repeatable read` the same
case becomes `could not serialize access`.

`FOR UPDATE` is pessimistic: the read holds the row. It has the variants `FOR NO
KEY UPDATE`, `FOR SHARE` and `FOR KEY SHARE`. `NOWAIT` fails immediately and
`SKIP LOCKED` skips locked rows, which is the pattern used for queues. Deadlock
is real when two transactions acquire in opposite orders, and `deadlock detected`
kills one of them.

The optimistic version uses `UPDATE ... WHERE id = ? AND version = ?`, where 0
rows affected means someone got there first. In JPA that is `@Version` and
`OptimisticLockException`. Worth watching: the database does not complain,
because 0 rows is success as far as it is concerned. Without checking the row
count, the failure is as silent as the one in Exp 1.

`SERIALIZABLE` is the only one that covers write skew (Exp C). The serious
restriction is that, if the transaction has an external effect before the commit,
such as a charge or an email, the retry repeats that effect.

Constraints do not coordinate, they refuse afterwards. Their value is making the
rule exist for the database. In Exp C, `sum(balance) >= 100` is not a row `CHECK`
because the rule does not live in any single row, and that is why it was
invisible.

Advisory locks are `pg_advisory_lock`, `pg_advisory_xact_lock` and
`pg_try_advisory_lock`. They are named by number, with no tie to any row.

## Evidence: what my experiments actually prove

A separate table for readability. There are three states. Proven means I ran it
and saw it. Indirect means my experiments show the problem the option addresses,
or its limit, but not the option working. No evidence means I never tested it.

| Option | State | Experiment |
|---|---|---|
| Relative expression in the `UPDATE` | indirect | Exp 1 and Exp A show the cost of deciding outside the database: the value written was computed earlier and went stale. I never ran `SET balance = balance - 10` |
| `SELECT ... FOR UPDATE` | proven | Exp E: waiting at the read produces a decision on fresh data (E.A); `NOWAIT` fails immediately (E.B); `lock_timeout` cancels as configured (E.C); a real deadlock with opposite orders, `ERROR: deadlock detected` (E.D) |
| Optimistic version | no evidence | I never created a version column nor tested a row count of 0 |
| `SERIALIZABLE` + retry | proven | Exp C: `could not serialize access due to read/write dependencies`, `Reason code: Canceled on identification as a pivot, during write`, `HINT: The transaction might succeed if retried`. Final state 140, invariant `t` |
| Declarative constraint | indirect (the limit only) | Exp C proves that `sum(balance) >= 100` cannot be expressed as a row `CHECK`, since the rule does not live in any single row. I never declared a constraint |
| Advisory lock | no evidence | |
| `LOCK TABLE` | no evidence | |

Outside the table of options, it is also proven that an abort with an explicit
error prevents lost update (Exp B, `could not serialize access due to concurrent
update`, at the `UPDATE` and not at the `COMMIT`), that dirty write is excluded
at every level (Exp A, from the waiting observed) and that lost update is silent
in `read committed` (Exp 1).

### Balance

Out of seven options, one has evidence of my own and two have indirect evidence.
The other four are knowledge from reading, probably correct but borrowed. No
decision should rest on them before I see them working.

Four experiments are missing to close the column: the relative expression, `FOR
UPDATE` (including `NOWAIT` and the deadlock case with opposite orders), the
optimistic version with a row count check, and a constraint refusing a write.

## Eliminations: from 7 to 2 (04/09/2026)

Two kinds of elimination, and the distinction matters. By measurement means I
have to run it to know. By structural property means the option has a
characteristic that rules it out regardless of any number. None of the
eliminations below needed an experiment.

### By structural property

`LOCK TABLE` serializes the whole table. It makes no sense to lock the entire
catalogue because someone is buying a single item. The cost is out of proportion
to the scope of the problem: I need to protect one row, not the table.

An advisory lock depends entirely on the programmer's discipline. If any code
path forgets to acquire the lock before writing, nothing protects anything, and
the omission does not produce an error. It produces silent corruption. Against an
invariant declared inviolable, that is fragile by construction.

A declarative constraint on its own decides nothing, it only accepts or refuses.
It cannot turn an order for 5 into a fulfilment of 3, which D2 requires. It stays
alive as a composition, a safety net that turns a silent violation into a loud
error.

### By product decision

The optimistic version and `SERIALIZABLE` + retry work by failing and trying
again. They contradict the decision recorded in [D6](product-decisions.md):
nobody fails from conflict, everyone waits.

An honest note: both are technically adequate for the problem. They were
eliminated by a behavioural choice, not for being unable to solve it. If the
product decision changes, they come back.

### Survivors

1. The relative expression in the `UPDATE`, which settles things inside the
   command, with no window between read and write. It has friction with D2: the
   quantity to deduct depends on the value read, so the command has to compute
   how much fitted *and* return that number to be recorded on the order.
2. `SELECT ... FOR UPDATE`, which settles things in the application, with the row
   held from the read onwards. It absorbs D2 effortlessly. Made viable by D4:
   since the cart does not reserve, the lock lasts milliseconds rather than the
   minutes a customer spends filling in a form.

A mistake made and corrected along the way: I discarded the relative expression
by blaming it for computing the value outside the database and letting it go
stale. It is the opposite. That was the flaw observed in Exp A and Exp D, and it
comes precisely from not using a relative expression. It computes inside the
command.

---

## Pending: the column that is still empty

What happens to whoever loses the contest? Do they wait, get an error, or does
the order simply vanish? That is a product decision, not a technical one, and no
row in the table answers it.

The table decides nothing on its own. What is missing is the shape of the
operation, the number of competitors for the same item, and what is acceptable
for whoever loses.
