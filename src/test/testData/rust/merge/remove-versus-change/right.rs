struct S;

impl S {
    fn keep(&self) {}
    fn gone(&self) {
        let _x = 1;
    }
}
