template <typename T, typename U = int>
struct Box {
    T value;
    U extra;
};

template <typename T>
T twice(T v) { return v + v + v; }
