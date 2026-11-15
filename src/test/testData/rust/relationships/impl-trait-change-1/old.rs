struct S;

trait A {
    fn f(&self) -> i32;
}

impl A for S {
    fn f(&self) -> i32 {
        1
    }
}
