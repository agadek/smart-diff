class Invoice {
    String render(String name, int count) {
        return heading(name, count) + "!";
    }

    String summary(String name, int count) {
        return heading(name, count) + "?";
    }

    private String heading(String name, int count) {
        StringBuilder text = new StringBuilder("Invoice: ");
        text.append(name).append(count);
        text.append(" lines");
        return text.toString();
    }
}
