package shop

type Order struct {
	Items []int
}

func (o *Order) Render() string {
	sum := 0
	for _, item := range o.Items {
		sum += item
	}
	return fmt.Sprintf("total %d", sum)
}
