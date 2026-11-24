#include <stdio.h>

static void body(const char **lines, int count) {
    for (int i = 0; i < count; i++) {
        printf("- %s\n", lines[i]);
    }
    printf("End of report with %d lines\n", count);
}

void report(const char **lines, int count) {
    printf("Report\n");
    body(lines, count);
}
