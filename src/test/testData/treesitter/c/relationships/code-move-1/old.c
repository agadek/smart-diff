int process(struct order *order) {
    if (save(order) != 0) {
        return -1;
    }
    notify(order->customer, order->id, "processed");
    audit(order->id, "processed");
    if (order->total <= 0 || order->item_count == 0) {
        return -2;
    }
    return 0;
}
