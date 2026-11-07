class Order {
    int total() { return 1; }
    int total(int value) { return value; }
    int use() {
        int a = total();
        int b = total(2);
        int c = unknown();
        return a + b + c;
    }
}
