#include <cmath>

class Coordinate {
public:
    double x;
    double y;

    double length() const {
        return std::sqrt(x * x + y * y);
    }
};
