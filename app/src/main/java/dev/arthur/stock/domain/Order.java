package dev.arthur.stock.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "requested_quantity", nullable = false)
    private int requestedQuantity;

    @Column(name = "fulfilled_quantity", nullable = false)
    private int fulfilledQuantity;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Order() {
    }

    public Order(Customer customer, Product product, int requestedQuantity, int fulfilledQuantity) {
        this.customer = customer;
        this.product = product;
        this.requestedQuantity = requestedQuantity;
        this.fulfilledQuantity = fulfilledQuantity;
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public Product getProduct() {
        return product;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getFulfilledQuantity() {
        return fulfilledQuantity;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
