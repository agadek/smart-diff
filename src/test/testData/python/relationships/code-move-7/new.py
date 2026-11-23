class OrderService:
    def process(self, order):
        self.validate(order)
        total = 0
        for item in order.items:
            total += item.price * item.count
        order.total = total
        self.repository.save(order)

    def validate(self, order):
        if order.customer is None or not order.items:
            raise ValueError("invalid order " + str(order.id))
