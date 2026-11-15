struct S;

trait A {
    fn f(&self);
}

impl A for S {
    fn f(&self) {
        let _x = 1;
    }
}
