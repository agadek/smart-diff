class Note:
    def title(self, name):
        return self.prefix(name) + "!"

    def prefix(self, name):
        return "Note: " + name
