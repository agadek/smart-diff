def outer():
    def inner():
        return 2
    return inner()
