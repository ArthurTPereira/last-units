package dev.arthur.stock.purchase;

public record PurchaseResult(long orderId, int requestedQuantity, int fulfilledQuantity) {

    public boolean declined() {
        return fulfilledQuantity == 0;
    }

    public boolean partial() {
        return fulfilledQuantity > 0 && fulfilledQuantity < requestedQuantity;
    }

    public boolean complete() {
        return fulfilledQuantity == requestedQuantity;
    }
}
