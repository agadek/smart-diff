class Invoice:
    def compute(self, x):
        doubled = x * 2
        plus = doubled + 1
        adjusted = plus * 2
        tripled = x * 3
        minus = tripled - 1
        trimmed = minus // 2
        return adjusted + trimmed
