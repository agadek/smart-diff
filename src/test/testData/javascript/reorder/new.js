import { format } from "./format.js";

export class Cart {
  items = [];

  total() {
    return this.items.reduce((sum, item) => sum + item.price, 0);
  }

  add(item) {
    this.items.push(item);
  }
}

export function describe(cart) {
  return format("Cart of " + cart.items.length);
}
