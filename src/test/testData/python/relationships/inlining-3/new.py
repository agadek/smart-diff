class Basket:
    def render(self, name, count):
        text = []
        text.append("Basket: ")
        text.append("owner " + str(name))
        text.append(" qty " + str(count))
        end = " / subtotal: " + str(name) + " x" + str(count) + " units"
        text.append(end)
        return "".join(text)
