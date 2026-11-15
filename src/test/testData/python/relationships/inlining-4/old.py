class Receipt:
    def total(self, customer, amount):
        return self.line(customer, amount) + "\n"

    def line(self, customer, amount):
        text = customer + ": " + str(amount) + " due"
        return text
