class Order:
    def render(self, name, count):
        text = "Report: " + str(name) + str(count) + " items"
        return text + "!"
