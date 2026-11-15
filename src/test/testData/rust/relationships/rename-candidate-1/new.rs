fn compute_sum(items: &[i32]) -> i32 {
    let mut sum = 0;
    for i in items {
        sum += *i;
    }
    sum
}
