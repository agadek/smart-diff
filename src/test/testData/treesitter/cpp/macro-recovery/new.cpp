#ifndef FMT_ARGS_H_
#define FMT_ARGS_H_

#include "base.h"

FMT_BEGIN_NAMESPACE
namespace detail {

template <typename T, FMT_ENABLE_IF(std::is_integral<T>::value)>
FMT_CONSTEXPR auto to_unsigned(T value) -> unsigned {
  return static_cast<unsigned>(value) & ~0u;
}

struct node {
  FMT_CONSTEXPR node(int v) : value(v) {}
  FMT_CONSTEXPR auto get() const -> int {
    if FMT_CONSTEXPR20 (sizeof(int) == 4) return value;
    return 0;
  }
  FMT_CONSTEXPR void set(int v) { value = v; }
  int value;
};

}  // namespace detail

/** An error. */
class FMT_SO_VISIBILITY("default") format_error : public std::runtime_error {
 public:
  using std::runtime_error::runtime_error;
};

FMT_EXPORT void report_error(const char* message);
FMT_END_NAMESPACE

#endif  // FMT_ARGS_H_
