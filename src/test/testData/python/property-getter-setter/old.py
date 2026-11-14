class C:
    @property
    def value(self):
        return self._v

    @value.setter
    def value(self, v):
        self._v = v
