class Invoice:
    def compute(self, x):
        return self.doubled(x) + self.trimmed(x)
    def doubled(self, x):
        doubled = x * 2
        plus = doubled + 1
        return plus * 2
    def trimmed(self, x):
        tripled = x * 3
        minus = tripled - 1
        return minus // 2
