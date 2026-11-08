class Order {
    int sum() { return 1; }
    int use(Order other) {
        int a = this.sum();
        int b = this.sum() + 1;
        return a + b;
    }
}
