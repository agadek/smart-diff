int process(struct order *order) {
    if (order->total <= 0 || order->item_count == 0) {
        return -2;
    }
    if (save(order) != 0) {
        return -1;
    }
    notify(order->customer, order->id, "processed");
    audit(order->id, "processed");
    return 0;
}
