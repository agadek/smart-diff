struct S;

trait A {
    fn f(&self) -> i32;
}

trait B {
    fn f(&self) -> i32;
}

impl B for S {
    fn f(&self) -> i32 {
        1
    }
}
