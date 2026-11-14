from typing import TYPE_CHECKING

if TYPE_CHECKING:
    class A:
        def m(self):
            pass
else:
    class A:
        pass
