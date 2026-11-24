class Cart {
public:
    int computeTotal(const int* prices, int count) const {
        int sum = 0;
        for (int i = 0; i < count; ++i) sum += prices[i];
        return sum;
    }
};
