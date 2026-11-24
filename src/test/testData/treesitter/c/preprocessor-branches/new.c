#ifdef DEBUG
static int level(void) { return 2; }
#else
static int level(void) { return 0; }
#endif

int run(void) { return level(); }
