class Cart {
public:
    int total(const int* prices, int count) const {
        int sum = 0;
        for (int i = 0; i < count; ++i) sum += prices[i];
        return sum;
    }
};
