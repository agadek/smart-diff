class Temperature {
  #celsius = 0;

  get celsius() {
    return this.#celsius;
  }

  set celsius(value) {
    if (value < -273.15) throw new RangeError("below absolute zero");
    this.#celsius = value;
  }
}
