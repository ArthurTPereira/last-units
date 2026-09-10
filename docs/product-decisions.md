# Product decisions and the master rule

## Master rule

### v1, 03/09/2026 (phase 0)

> A customer can never buy an order that has run out. They must be told the product is unavailable.

Problems found later: it mixes up order and product; it does not define the
instant at which "buying" happens; it does not tell "sold out" apart from "every
unit is held in someone else's order"; it merges a data invariant with a
communication requirement; it is one-sided, since a system that never sells
anything satisfies it; and it does not cover partial fulfilment.

### v2, 04/09/2026

> At no instant may the total committed units of a product exceed the total
> units that exist.
>
> When an order cannot be fulfilled in full, the system confirms only what
> exists and informs the customer before any charge. Any quantity above zero is
> valid fulfilment. When nothing can be fulfilled, it declines.
>
> No sale is undone for lack of stock. A refund is not a planned path.

### Operational definitions

| Term | Definition |
|---|---|
| committed | a unit confirmed in an order plus a unit held in an open payment window. Both count the same |
| existing | the number recorded in the system. Divergence from the physical warehouse is a different problem, outside this invariant |
| at no instant | no committed transaction may observe or produce a state that breaks the rule. An invisible intermediate state does not count |
| inform | a synchronous response from the operation itself, not a later notification |
| charge | any irreversible effect outside the system |

---

## Decisions

### D1: no overselling. An unacceptable violation, not a repairable incident.

Selling one unit too many is not treated as an incident with a known cost. I
prefer an error at checkout over letting the customer complete the purchase and
then having to refund it.

The consequence is that the project takes the *guarantee* of the ticketing
pattern with the *experience* of the e-commerce pattern. That combination is
what keeps alive the problem the project is named after. Adopting pure
e-commerce, accepting overselling and compensating afterwards, would dissolve it.

### D2: partial fulfilment is valid.

Asked for 5, 3 exist: sell 3 and say so. Any quantity above zero works, with no
minimum. Real marketplaces sometimes require a minimum per product, but that
stayed out of scope, chosen this way to simplify without losing the problem.

Left open: if the available quantity changes between the read and the
confirmation, does the customer confirm again or does the system assume they
accept? Not decided.

### D3: order A, commit stock and then charge.

This is not an independent choice. It follows from D1, since refusing refunds
rules out the reverse order.

The reasoning is that the customer has to feel safe buying. Whoever carries the
cost of holding a product for a possible buyer is the shop. Charging and then
saying there is no product is disrespectful with the customer's money. In a
physical shop, no clerk charges for something that is not on the shelf.

What it costs: units are held before any money exists. If the charge is slow or
fails, those units were unavailable to someone who would have paid, and somebody
has to give them back.

That brings work along: how long the payment window lasts, who returns the stock
when it expires, what happens if the process dies with the window open, and
which clock is authoritative.

### D4: the cart does not reserve.

The contest is settled when the order is confirmed, not when the item goes into
the cart. This follows the e-commerce pattern.

The consequence is that the only window in which stock is held is the payment one
(D3). If payment were synchronous and instant, there would be no reservation in
the system at all.

### D6: nobody fails from routine conflict. Everyone waits, with a limit.

Between a system where nobody fails and everyone waits, and one where nobody
waits and some fail and are retried, I chose the first.

Chronologically this comes before D5, which quantifies it.

The reasoning is that a queue is predictable: everyone is served, in order, and
the worst case is the whole queue. The optimistic model is fast on average and
unfair in the tail, because under high contention an unlucky request may collide
several times in a row while later arrivals are already done.

What it costs is higher average latency, and a serialization point that becomes
the bottleneck of the system. Exp D.2 confirms it: contention on the row
dominates latency, to the point where widening the connection pool made things
worse.

This rules out the optimistic version and `SERIALIZABLE` with retry. Both are
adequate for the problem and were discarded by a behavioural choice, not for
being unable to solve it.

#### The promise, stated exactly (refined 09/09/2026, after Exp E)

1. Nobody fails from routine conflict. Contention on the same row resolves as a
   queue: whoever arrives later waits, reads the updated state and decides on
   fresh data (Exp E.A).
2. Someone may fail from excessive delay. The limit is D5.
   `ERROR: canceling statement due to lock timeout` (Exp E.C).
3. Someone may fail from deadlock. `ERROR: deadlock detected` (Exp E.D).

The third clause came from evidence, not from reading. Two properties make it
acceptable. It does not exist in V1, where there is one order, one product and
one row, and deadlock is impossible without multiple resources. And it is
avoidable by construction, requiring a deterministic acquisition order, which is
the same item already written in the "requires from the code" column of the phase
3 table, noted from reading before there was any evidence.

The requirement this creates: once an order may contain several products, which
is in the backlog, acquisition order stops being an implementation detail and
becomes part of this decision.

### D5: waiting budget (revised 09/09/2026, after Exp F)

| Setting | Value | Status |
|---|---|---|
| Total request budget | 3 s | kept, from the page abandonment study |
| `statement_timeout` | 3 s | kept |
| `lock_timeout` | 1 s | confirmed by Exp F |
| `connection-timeout` (HikariCP) | 1 s, proposed | deduction, to be confirmed |
| Acceptable technical abort rate | 1% or less, proposed | deduction, to be confirmed |

#### What Exp F confirmed

The 1 s `lock_timeout` was set to mean "this is not congestion, this is
something stuck". The sweep shows it does exactly that: with 600 concurrent
requests on the same row and a total latency of 1570 ms, there were zero aborts.
The real wait on the row sits between 200 ms and 1 s (Exp F.4).

Right calibration, for the right reason.

#### The hole Exp F revealed

Neither timeout protects the 3 s budget.

Most of the latency is not waiting on the row. It is waiting for a connection
from the pool, which happens before any command reaches the database, and
`lock_timeout` and `statement_timeout` are blind to it.

And HikariCP has a `connection-timeout` that defaults to 30 seconds. Today a
request can wait half a minute for a connection without any configured limit
noticing.

Proposal: a `connection-timeout` of 1 s. The reasoning is that the 3 s budget
splits between waiting for a connection, executing and returning over the
network, so 1 s for the first stage leaves 2 s for the rest. It is a deduced
number, not a measured one. Confirm it by measuring the pool queue separately.

#### Abort rate: the promise needs a number

Exp F.5 showed the test passing with 394 of 400 requests aborted. The invariant
was intact; the service promise was not.

D6 says that nobody fails from routine conflict and that someone may fail from
excessive delay. Without a number, that second clause is not verifiable, and the
test approves anything.

Proposal: at most 1% technical aborts in the nominal scenario, with 200 clients,
a pool of 10 and a 1 s `lock_timeout`, as an assertion separate from the
invariant. What we see today is zero.

Record it as a distinct assertion, because correctness and quality of service
fail for different reasons and should fail with different messages.

#### Carried over from the previous version

A mistake I nearly made: using the 3 s from the UX study as the timeout for
waiting on a row. Those 3 s are total perceived time, and waiting on a row is one
share of it. A budget is divided, not spent entirely on the first stage. Exp F
showed the intuition was even more wrong than it looked, because waiting on the
row is the smallest share.

Whoever exceeds the limit gets an error, with a message that does not lie about
the cause. It is not "product unavailable", because the product may exist, and it
is not "unexpected error", because it is predicted, documented and configured.

Pending: confirm the difference between `lock_timeout` and `statement_timeout`
with an experiment of my own. Exp E.C exercised only the first.

---

## Out of scope, noted

- Customer-requested cancellation. When it comes in, it returns a unit to stock
  and becomes restocking concurrent with selling.
- Minimum quantity per product.
- Divergence between recorded stock and the physical warehouse.
