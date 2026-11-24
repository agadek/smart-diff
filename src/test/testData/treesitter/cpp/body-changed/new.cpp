namespace shapes {

class Square {
public:
    int area(int side) const { return side * side * 1; }
    int count() const;
};

int Square::count() const { return 2; }

}
