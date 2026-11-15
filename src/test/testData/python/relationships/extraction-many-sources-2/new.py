class Invoice:
    def first(self, x):
        return self.doubled(x) + 1
    def second(self, x):
        return self.doubled(x) * 3
    def doubled(self, x):
        doubled = x * 2
        result = doubled + 1
        return result
