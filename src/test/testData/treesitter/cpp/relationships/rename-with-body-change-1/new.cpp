#include <string>

std::string formatAmount(int cents) {
    int dollars = cents / 100;
    int rest = cents % 100;
    std::string padded = rest < 10 ? "0" + std::to_string(rest) : std::to_string(rest);
    return "USD " + std::to_string(dollars) + "." + padded;
}

std::string formatCount(int count) {
    return std::to_string(count) + " items";
}
