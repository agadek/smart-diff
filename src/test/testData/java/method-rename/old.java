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

    public int computeTotal() {
        int sum = 0;
        for (Item item : entries) {
            sum += item.price();
        }
        return sum;
    }

    public int countItems() {
        return countFrom(0);
    }

    private int countFrom(int start) {
        return start >= entries.size() ? 0 : 1 + countFrom(start + 1);
    }

    void reset() {}

    record Item(String name, int price) {}
}
