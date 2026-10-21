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
        add(new Item(name, price));
    }

    public int total() {
        int sum = 0;
        for (Item item : items {
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
