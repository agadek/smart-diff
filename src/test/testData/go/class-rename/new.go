package shop

import "fmt"

type Purchase struct {
	ID    int
	Items []Item
}

func (o *Purchase) Total() int {
	sum := 0
	for _, item := range o.Items {
		sum += item.Price
	}
	return sum
}

func (o *Purchase) Describe() string {
	return fmt.Sprintf("order %d", o.ID)
}
