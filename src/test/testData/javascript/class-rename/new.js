import { format } from "./format.js";

export class Basket {
  items = [];

  add(item) {
    this.items.push(item);
  }

  total() {
    return this.items.reduce((sum, item) => sum + item.price, 0);
  }
}

export function describe(cart) {
  return format("Cart of " + cart.items.length);
}
