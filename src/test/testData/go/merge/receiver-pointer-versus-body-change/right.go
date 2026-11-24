package shop

type Order struct {
	Value int
}

func (o *Order) Total() int {
	return o.Value * 2
}
