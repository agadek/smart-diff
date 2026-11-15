struct S;

trait A {
    fn f(&self);
}

trait B {
    fn f(&self);
}

impl B for S {
    fn f(&self) {}
}
