class Basket:
    def render(self, lines):
        out = []
        for line in lines:
            out.append(line + "\n")
        return "".join(out)
