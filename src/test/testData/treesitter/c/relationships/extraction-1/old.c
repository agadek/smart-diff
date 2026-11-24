#include <stdio.h>

void report(const char **lines, int count) {
    printf("Report\n");
    for (int i = 0; i < count; i++) {
        printf("- %s\n", lines[i]);
    }
    printf("End of report with %d lines\n", count);
}
