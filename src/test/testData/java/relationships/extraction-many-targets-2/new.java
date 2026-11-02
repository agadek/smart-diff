class Invoice {
    int compute(int x) {
        return doubled(x) + trimmed(x);
    }
    int doubled(int x) {
        int doubled = x * 2;
        int plus = doubled + 1;
        return plus * 2;
    }
    int trimmed(int x) {
        int tripled = x * 3;
        int minus = tripled - 1;
        return minus / 2;
    }
}
