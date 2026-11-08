class Basket {
    String render(String name, int count) {
        StringBuilder text = new StringBuilder();
        text.append("Basket: ");
        text.append("owner ").append(name);
        text.append(" qty ").append(count);
        StringBuilder end = new StringBuilder(" / ");
        end.append("subtotal: ").append(name);
        end.append(" x").append(count).append(" units");
        text.append(end);
        return text.toString();
    }
}
