package shop

type Order struct {
	Items []int
}

func (o *Order) Render() string {
	return fmt.Sprintf("total %d", o.sumItems())
}

func (o *Order) sumItems() int {
	sum := 0
	for _, item := range o.Items {
		sum += item
	}
	return sum
}
