class Report:
    def process(self, orders):
        total = 0
        for order in orders:
            total += order.total
        self.total = total

    def finish(self):
        self.writer.flush()
        for order in orders:
            self.logger.info("processed order " + str(order.id) + " for " + order.customer)
        self.writer.close()
