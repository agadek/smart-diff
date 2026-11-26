#ifndef JV_H
#define JV_H

#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct { int kind; } jv;

jv jv_string_fmt(const char *fmt, ...) JV_PRINTF_LIKE(1, 3);

#ifndef WIN32
static
#endif
int jv_count(jv a) {
  int n = 0;
  jv_array_foreach(a, i, x) {
#ifdef USE_DECNUM
    if (decnum(x))
#else
    if (is_number(x))
#endif
      n++;
  }
  return n;
}

#ifdef __cplusplus
}
#endif

#endif
