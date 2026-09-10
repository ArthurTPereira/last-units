-- Core tables: customer, product and orders.

-- This schema does not protect the stock invariant. This is a deliberate choice, not an omission.
-- No check for a non-negative quantity, no version column, and no constraint preventing a sale larger than the stock
-- The guarantee is left to the application layer (See docs/technical-decision.md for the reasoning behind this decision.)

CREATE TABLE customer (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE product (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku                text NOT NULL UNIQUE,
    name               text NOT NULL,
    available_quantity integer NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id        bigint NOT NULL REFERENCES customer(id),
    product_id         bigint NOT NULL REFERENCES product(id),
    requested_quantity integer NOT NULL,
    fulfilled_quantity integer NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_product ON orders (product_id);
