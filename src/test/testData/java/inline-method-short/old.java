class Note {
    String title(String name) {
        return prefix(name) + "!";
    }

    private String prefix(String name) {
        return "Note: " + name;
    }
}
