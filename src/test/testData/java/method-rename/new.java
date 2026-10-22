package shop;

import java.util.ArrayList;
import java.util.List;

/** A shopping cart. */
public class Cart {
    private final List<Item> entries = new ArrayList<>();

    public Cart() {
    }

    public void put(Item item) {
        entries.add(item);
        System.out.println("added " + item.name());
    }

    public int total() {
        int sum = 0;
        for (Item item : entries) {
            sum += item.price();
        }
        return sum;
    }

    public int size() {
        return sizeFrom(0);
    }

    private int sizeFrom(int start) {
        return start >= entries.size() ? 0 : 1 + sizeFrom(start + 1);
    }

    void clear() {}

    record Item(String name, int price) {}
}
