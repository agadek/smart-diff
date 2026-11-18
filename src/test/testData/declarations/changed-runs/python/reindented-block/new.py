class Cart:
    def clear(self):
        if self.items:
            self.items.clear()
            self.total = 0
