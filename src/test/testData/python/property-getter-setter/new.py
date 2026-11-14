class C:
    @property
    def value(self):
        return self._v + 1

    @value.setter
    def value(self, v):
        self._v = v
