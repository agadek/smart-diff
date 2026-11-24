class OrderService {
  process(orders) {
    for (const order of orders) {
      validate(order);
    }
    const total = orders.length;
    if (total > 10) {
      console.log("big batch " + total);
      audit.record("batch", total);
    }
    save(orders);
  }

  finish(orders) {
    notify(orders);
  }
}
