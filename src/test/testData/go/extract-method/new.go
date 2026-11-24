package report

func Render(items []int) int {
	return sumItems(items)
}

func sumItems(items []int) int {
	sum := 0
	for _, i := range items {
		sum += i
	}
	return sum
}
