class OrderService {
  process(orders) {
    for (const order of orders) {
      validate(order);
    }
    save(orders);
  }

  finish(orders) {
    const total = orders.length;
    if (total > 10) {
      console.log("big batch " + total);
      audit.record("batch", total);
    }
    notify(orders);
  }
}
