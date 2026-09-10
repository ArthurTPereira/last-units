package dev.arthur.stock.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    // Nothing stops this value from going negative, not the database, neither this class.
    // Deliberate to allow the concurrency test to fail.
    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Product() {
    }

    public Product(String sku, String name, int availableQuantity) {
        this.sku = sku;
        this.name = name;
        this.availableQuantity = availableQuantity;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public void setAvailableQuantity(int availableQuantity) {
        this.availableQuantity = availableQuantity;
    }
}
