class OrderService:
    def process(self, order):
        if order.customer is None or not order.items:
            raise ValueError("order is invalid: " + str(order.id))
        total = 0
        for item in order.items:
            total += item.price * item.count
        order.total = total
        self.repository.save(order)
