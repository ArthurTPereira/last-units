# Technical decision: concurrency control mechanism

Date: 09/09/2026

## Context

The problem is that naive V1 sells 28 to 32 units out of a stock of 3, with 200 simultaneous requests, without an error and without a log entry. The evidence is in Exp D, in [experiments.md](experiments.md).

The invariant to guarantee is master rule v2, in
[product-decisions.md](product-decisions.md).

The brainstorm narrowed 7 candidates down to 2 in
[phase-3-mechanisms.md](phase-3-mechanisms.md).

The product decisions that constrain the choice are D1 (no overselling), D2 (partial fulfilment), D3 (stock before charging), D4 (the cart does not reserve), D5 (waiting budget) and D6 (waiting instead of failing on conflict).

---

## 1. Chosen mechanism

`SELECT ... FOR UPDATE`: pessimistic row locking, acquired at the read, inside the transaction that decides and writes.

## 2. Why

Because it moves the wait to **before** the decision.

Exp A and Exp E.A both produce waiting, and give opposite results:

| | Exp A (naive V1) | Exp E.A (`FOR UPDATE`) |
|---|---|---|
| Where the wait happens | after the decision | at the read, before the decision |
| Value written | computed from stale data | computed from fresh data |
| Result | 90, wrong | 80, correct |

In Exp A, the transaction paused and later wrote the stale value it had calculated before waiting. The lock protected the write, but not the decision. With `FOR UPDATE` the wait happens during the read, so when the transaction unblocks, it has not decided anything yet, so it reads the actual final state.

It is the same wait but in a different position in the sequence, and the position is what produces the correct result.

On top of that, it is the only one of the two finalists with evidence of my own.

## 3. The closest alternative, and why not it

The relative expression in the `UPDATE`, in the form `SET quantity = quantity - :n`.

It handles the read and write in a single command, completely eliminating any gap between them. This keeps lock times to an absolute minimum by holding the row for just a single command instead of an entire transaction.

Three reasons against it:

1. The application never sees the result because the read happens entirely within the command. Without a RETURNING clause or an extra SELECT, you have no way of knowing how much was actually deducted before committing.
2. Readability. The business rule moves into a SQL expression, and the values read never appear anywhere in the code. That is a preference rather than a correctness issue, but it is a preference with maintenance consequences.
3. It has no evidence of my own. It was not tested. 

## 4. What it costs

A longer lock time. The lock lasts until the transaction commits, instead of just until the command completes.

From the initial `SELECT ... FOR UPDATE` all the way to the final `COMMIT`, the database has to handle network travel time, Java logic execution, the UPDATE, the order INSERT, and the commit itself. The row stays locked the entire time, forcing every subsequent transaction to wait in line. A relative expression cuts that lock time down to a single command. This widens the serialization point, causing the queue to grow in step.

The latency difference between the two options was not measured. Good point to measure by yourself if you want it.
