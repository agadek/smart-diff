package shop

import "fmt"

type Order struct {
	ID    int
	Items []Item
}

func (o *Order) Describe() string {
	return fmt.Sprintf("order %d", o.ID)
}

func (o *Order) Total() int {
	sum := 0
	for _, item := range o.Items {
		sum += item.Price
	}
	return sum
}
