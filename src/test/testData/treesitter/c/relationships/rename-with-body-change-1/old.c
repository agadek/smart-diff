void format_price(char *out, int cents) {
    int dollars = cents / 100;
    int rest = cents % 100;
    const char *pad = rest < 10 ? "0" : "";
    sprintf(out, "$%d.%s%d", dollars, pad, rest);
}

void format_count(char *out, int count) {
    sprintf(out, "%d items", count);
}
