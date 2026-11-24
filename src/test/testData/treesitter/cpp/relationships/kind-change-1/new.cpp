#include <string>

struct Money {
    int cents;
    std::string currency;

    Money(int cents, std::string currency) : cents(cents), currency(currency) {}
};
