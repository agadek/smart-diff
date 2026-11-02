class Basket {
    String render(List<String> lines) {
        return renderLines(lines);
    }

    private String renderLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
