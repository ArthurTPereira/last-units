# Scaling backlog

Material for the scaling strategy document: where it breaks first, and what I
would do at 10x and 100x. None of it goes into the current version, since the
scope is locked to one service, one database, one process.

A note on sources: what follows comes from engineering blogs, talks and product
documentation. It is not verified internal architecture of any company.

---

## Separating catalogue from stock

A common pattern in marketplaces is that catalogue and stock live in different
stores. There are two reasons, and consistency is the second one.

The first is the access pattern. A catalogue is massive reading with search,
filtering and faceting: millions of reads, rare writes. Stock is writing
concentrated on a few hot items. The workloads are opposites, and optimizing one
hurts the other.

The second is consistency. A product description that is 30 seconds stale hurts
nobody. A stock counter that is 30 seconds stale sells what does not exist.

### The real axis is not SQL versus NoSQL

The phrasing "catalogue in NoSQL, stock in SQL" is imprecise. Several
non-relational databases offer transactions and conditional writes. DynamoDB has
ACID transactions and conditional operations, and there is stock control running
on NoSQL without breaking any invariant.

The correct sentence is a different one: stock needs a store that guarantees the
invariant under concurrency. Relational or not is a consequence, not a
requirement.

It is the same lesson as Exp D by another route. What protects the rule is not
the technology, it is the guarantee the technology offers and what was expressed
in it. A badly used Postgres loses sales silently: 200 requests, 3 units, 28
sold, no error.

---

## Stock at high volume

What gets published is that the number is rarely a single row.

- stock partitioned by distribution centre or region;
- availability computed as an aggregation of several sources;
- reservations made against a partition rather than the total.

A consequence to investigate when the time comes: partitioning the counter trades
one contention point for several, at the cost of the invariant no longer living
in a row and moving into a sum. That is the category from Exp C, the one
`repeatable read` does not catch.

---

## Tolerated overselling, the path this project refused

Several large marketplaces accept overselling and cancel afterwards. For them,
selling one unit too many is an incident with a known cost, and the refund
operation exists anyway.

That is pattern A, examined and rejected in [D1](product-decisions.md).

It is worth recording it that way in the final document, because it is the
strongest argument for the choice: a system that can cancel later does not have
to solve the hard problem. Refusing to oversell is what gives the project its
substance.

---

## A finding of my own, with evidence

Widening the connection pool made things worse. In Exp D.2, with a pool of 10 the
p50 landed between 305 and 364 ms; with a pool of 50, between 442 and 464 ms. The
bottleneck is contention on the product row, not the connection queue, and the
pool was acting as a concurrency limiter without anyone having designed it that
way.

On a hot resource, more concurrency at the same point makes things worse. It is
worth keeping as a warning in the 10x section: raising the pool is the first
thing anyone tries, and here it would have the opposite effect.

This is the only item in this file with a measurement of my own. The rest is
reading.
