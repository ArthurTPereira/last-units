package dev.arthur.stock.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("select coalesce(sum(o.fulfilledQuantity), 0) from Order o where o.product.id = :productId")
    long totalFulfilledForProduct(@Param("productId") long productId);
}
