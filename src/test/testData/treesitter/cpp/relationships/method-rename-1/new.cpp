#include <vector>

class Stats {
public:
    int sum(const std::vector<int>& values) const {
        int sum = 0;
        for (int value : values) sum += value;
        return sum;
    }

    int largest(const std::vector<int>& values) const {
        int best = values.front();
        for (int value : values) if (value > best) best = value;
        return best;
    }
};
