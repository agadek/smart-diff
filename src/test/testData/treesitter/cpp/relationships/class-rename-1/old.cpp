#include <cmath>

class Point {
public:
    double x;
    double y;

    double length() const {
        return std::sqrt(x * x + y * y);
    }
};
