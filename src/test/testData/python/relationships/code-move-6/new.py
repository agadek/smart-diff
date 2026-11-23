class OrderService:
    def process(self, order):
        self.logger.info("done")
        total = 0
        for item in order.items:
            total += item.price * item.count
        order.total = total
        self.repository.save(order)
        self.logger.info("start")
