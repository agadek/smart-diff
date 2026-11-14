from typing import overload

@overload
def f(x: int, y: int) -> int: ...
@overload
def f(x: str, y: str) -> str: ...
def f(x, y=None):
    return x
