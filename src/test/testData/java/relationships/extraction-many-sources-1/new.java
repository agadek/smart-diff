class Order {
    int first(int x) {
        return doubled(x) + 1;
    }
    int second(int x) {
        return doubled(x) * 3;
    }
    int doubled(int x) {
        int doubled = x * 2;
        int result = doubled + 1;
        return result;
    }
}
