class Cart:
    def reset(self):
        log("reset")
        self.items.clear()
        self.total = 0
