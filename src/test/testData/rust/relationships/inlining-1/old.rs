struct Order;

impl Order {
    fn render(&self, items: &[i32]) -> i32 {
        self.sum_items(items)
    }

    fn sum_items(&self, items: &[i32]) -> i32 {
        let mut sum = 0;
        for i in items {
            sum += *i;
        }
        sum
    }
}
