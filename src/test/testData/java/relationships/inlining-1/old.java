class Order {
    String render(String name) {
        return heading(name) + "!";
    }

    private String heading(String name) {
        String h = "Report: " + name;
        return h;
    }
}
