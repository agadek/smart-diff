class Order {
    String render(String name, int count) {
        return heading(name, count) + "!";
    }

    private String heading(String name, int count) {
        StringBuilder text = new StringBuilder("Report: ");
        text.append(name).append(count);
        text.append(" items");
        return text.toString();
    }
}
