class Order {
    String render(String name, int count) {
        StringBuilder text = new StringBuilder("Report: ");
        text.append(name).append(count);
        text.append(" items");
        return text.toString() + "!";
    }
}
