class Cart:
    def total(self, limit):
        sum = 0
        for item in self.items: sum += item.price if item.price >= limit else 0
        return sum
