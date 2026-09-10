package dev.arthur.stock.purchase;

import dev.arthur.stock.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// V2, pessimistic row locking acquired at the read.
@Service
@ConditionalOnProperty(name = "stock.purchase.mode", havingValue = "safe", matchIfMissing = true)
public class SafePurchase implements PurchaseService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public SafePurchase(CustomerRepository customerRepository, OrderRepository orderRepository,
            ProductRepository productRepository) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Override
    @Transactional
    public PurchaseResult purchase(long customerId, long productId, int requestedQuantity) {

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        // From here on the product row is held until commit. Pretty simple, but it makes all the difference.
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        int fulfilledQuantity = Math.min(product.getAvailableQuantity(), requestedQuantity);

        product.setAvailableQuantity(product.getAvailableQuantity() - fulfilledQuantity);

        Order order = orderRepository.save(
                new Order(customer, product, requestedQuantity, fulfilledQuantity));

        return new PurchaseResult(order.getId(), requestedQuantity, fulfilledQuantity);
    }
}
