class Receipt:
    def total(self, customer, amount):
        text = customer + ": " + str(amount) + " due"
        return text + "\n"
