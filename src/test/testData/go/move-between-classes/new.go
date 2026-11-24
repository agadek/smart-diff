package geo

type Point struct {
	X int
	Y int
}

type Line struct {
	From  Point
	To    Point
	Label string
}
