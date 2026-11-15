struct Order {
    value: i32,
}

impl Order {
    fn total(&self) -> i32 {
        self.value + 1
    }
}
