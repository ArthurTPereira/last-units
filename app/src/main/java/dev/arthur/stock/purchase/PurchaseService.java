package dev.arthur.stock.purchase;

public interface PurchaseService {

    PurchaseResult purchase(long customerId, long productId, int requestedQuantity);
}
