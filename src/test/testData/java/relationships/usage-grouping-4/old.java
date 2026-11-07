class Order {
    int total() { return 1; }
    int use(Order other) {
        int a = this.total();
        int b = other.total() + 1;
        return a + b;
    }
}
