class Cart {
    int total(int limit) {
        int sum = 0;
        for (Item item : items) {
            if (item.price() >= limit) sum += item.price();
        }
        return sum;
    }
}
