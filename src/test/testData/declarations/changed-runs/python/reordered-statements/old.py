class Cart:
    def reset(self):
        self.items.clear()
        self.total = 0
        log("reset")
