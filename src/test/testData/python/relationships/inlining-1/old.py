class Order:
    def render(self, name, count):
        return self.heading(name, count) + "!"

    def heading(self, name, count):
        text = "Report: " + str(name) + str(count) + " items"
        return text
