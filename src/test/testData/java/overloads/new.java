package shop;

import java.util.ArrayList;
import java.util.List;

/** A shopping cart. */
public class Cart {
    private final List<Item> items = new ArrayList<>();

    public void add(Item item) {
        items.add(item);
    }

    public void add(String name, int price) {
        add(new Item(name.trim(), price));
    }

    public void add(Item item, int quantity) {
        for (int i = 0; i < quantity; i++) {
            add(item);
        }
    }

    public int total() {
        int sum = 0;
        for (Item item : items) {
            sum += item.price();
        }
        return sum;
    }

    public String describe() {
        return "Cart with " + items.size() + " items";
    }

    record Item(String name, int price) {}
}

class Receipt {
    String header() {
        return "Receipt";
    }
}
