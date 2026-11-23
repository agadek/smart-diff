class OrderService:
    def process(self, order):
        total = 0
        for item in order.items:
            total += item.price * item.count
        order.total = total
        self.repository.save(order)
        self.audit.log(order)
