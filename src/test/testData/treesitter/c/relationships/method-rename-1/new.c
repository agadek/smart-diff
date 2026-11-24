int sum_values(const int *values, int count) {
    int sum = 0;
    for (int i = 0; i < count; i++) sum += values[i];
    return sum;
}

int largest(const int *values, int count) {
    int best = values[0];
    for (int i = 1; i < count; i++) if (values[i] > best) best = values[i];
    return best;
}
