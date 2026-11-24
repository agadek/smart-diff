#include <vector>

int sum(const std::vector<int> &values)
{
    int s = 0;
    for (int v : values)
        s += v;
    return s;
}

int twice(int v) { return v * 2; }
