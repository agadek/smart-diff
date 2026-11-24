#include <vector>

int third(const std::vector<int>& values) {
    return values.size() > 2 ? values[2] : 0;
}

int first(const std::vector<int>& values) {
    return values.size() > 0 ? values[0] : 0;
}

int second(const std::vector<int>& values) {
    return values.size() > 1 ? values[1] : 0;
}
