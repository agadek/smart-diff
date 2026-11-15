class Invoice:
    def render(self, name, count):
        text = "Invoice: " + str(name) + str(count) + " lines"
        return text + "!"

    def summary(self, name, count):
        text = "Invoice: " + str(name) + str(count) + " lines"
        return text + "?"
