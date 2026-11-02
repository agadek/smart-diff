class Basket {
    int compute(int x) {
        int doubled = x * 2;
        int plus = doubled + 1;
        int adjusted = plus * 2;
        int tripled = x * 3;
        int minus = tripled - 1;
        int trimmed = minus / 2;
        return adjusted + trimmed;
    }
}
