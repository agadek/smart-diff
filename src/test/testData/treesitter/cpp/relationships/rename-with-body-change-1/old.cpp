#include <string>

std::string formatPrice(int cents) {
    int dollars = cents / 100;
    int rest = cents % 100;
    std::string padded = rest < 10 ? "0" + std::to_string(rest) : std::to_string(rest);
    return "$" + std::to_string(dollars) + "." + padded;
}

std::string formatCount(int count) {
    return std::to_string(count) + " items";
}
