class Invoice {
    String render(String name, int count) {
        StringBuilder text = new StringBuilder("Invoice: ");
        text.append(name).append(count);
        text.append(" lines");
        return text.toString() + "!";
    }

    String summary(String name, int count) {
        StringBuilder text = new StringBuilder("Invoice: ");
        text.append(name).append(count);
        text.append(" lines");
        return text.toString() + "?";
    }
}
