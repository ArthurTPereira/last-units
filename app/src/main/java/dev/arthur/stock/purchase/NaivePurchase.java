package dev.arthur.stock.purchase;

import dev.arthur.stock.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// V1, the broken version, kept alive on purpose. (DO NOT FIX IT)
// The worst part is that none of this produces an error. No exception, no log entry, and the final counter is a plausible number.
@Service
@ConditionalOnProperty(name = "stock.purchase.mode", havingValue = "naive")
public class NaivePurchase implements PurchaseService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public NaivePurchase(CustomerRepository customerRepository, OrderRepository orderRepository,
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

        // A read that does not hold the row. Pretty common to occur in a naive implementation. 
        // Apparently, nothing is wrong with it (I have made code myself before that did this).
        // Not really a problem until you have concurrent requests.
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        int fulfilledQuantity = Math.min(product.getAvailableQuantity(), requestedQuantity);

        product.setAvailableQuantity(product.getAvailableQuantity() - fulfilledQuantity);

        Order order = orderRepository.save(
                new Order(customer, product, requestedQuantity, fulfilledQuantity));

        return new PurchaseResult(order.getId(), requestedQuantity, fulfilledQuantity);
    }
}
