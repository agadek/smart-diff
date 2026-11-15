class Invoice:
    def render(self, name, count):
        return self.heading(name, count) + "!"

    def summary(self, name, count):
        return self.heading(name, count) + "?"

    def heading(self, name, count):
        text = "Invoice: " + str(name) + str(count) + " lines"
        return text
