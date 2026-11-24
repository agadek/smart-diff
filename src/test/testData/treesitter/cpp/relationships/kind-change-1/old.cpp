#include <string>

class Money {
public:
    int cents;
    std::string currency;

    Money(int cents, std::string currency) : cents(cents), currency(currency) {}
};
