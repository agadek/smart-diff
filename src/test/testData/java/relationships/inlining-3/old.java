class Basket {
    String render(String name, int count) {
        StringBuilder text = new StringBuilder();
        heading(name, count, text);
        footer(name, count, text);
        return text.toString();
    }

    private void heading(String name, int count, StringBuilder text) {
        text.append("Basket: ");
        text.append("owner ").append(name);
        text.append(" qty ").append(count);
    }

    private void footer(String name, int count, StringBuilder text) {
        StringBuilder end = new StringBuilder(" / ");
        end.append("subtotal: ").append(name);
        end.append(" x").append(count).append(" units");
        text.append(end);
    }
}
