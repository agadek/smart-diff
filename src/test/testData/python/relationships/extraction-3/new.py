class Basket:
    def render(self, lines):
        return self.render_lines(lines)

    def render_lines(self, lines):
        out = []
        for line in lines:
            out.append(line + "\n")
        return "".join(out)
