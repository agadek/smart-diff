int third(const int *values, int count) {
    return count > 2 ? values[2] : 0;
}

int first(const int *values, int count) {
    return count > 0 ? values[0] : 0;
}

int second(const int *values, int count) {
    return count > 1 ? values[1] : 0;
}
