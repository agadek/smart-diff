class Basket:
    def render(self, name, count):
        text = []
        self.heading(name, count, text)
        self.footer(name, count, text)
        return "".join(text)

    def heading(self, name, count, text):
        text.append("Basket: ")
        text.append("owner " + str(name))
        text.append(" qty " + str(count))

    def footer(self, name, count, text):
        end = " / subtotal: " + str(name) + " x" + str(count) + " units"
        text.append(end)
