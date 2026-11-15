class Order:
    def render(self, name, count):
        return self.heading(name, count)

    def heading(self, name, count):
        return name + str(count)
