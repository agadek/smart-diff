import { format } from "./format.js";

export class Cart {
  items = [];

  add(item) {
    // Items keep their insertion order.
    this.items.push(item);
  }

  total() {
    return this.items.reduce((sum, item) => sum + item.price, 0);
  }
}

export function describe(cart) {
  return format("Cart of " + cart.items.length);
}
