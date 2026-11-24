package geo

type Point struct {
	X     int
	Y     int
	Label string
}

type Line struct {
	From Point
	To   Point
}
