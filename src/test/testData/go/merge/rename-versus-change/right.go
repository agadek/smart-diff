package calc

func Total(values []int) int {
	sum := 0
	for _, v := range values {
		sum += v * 2
	}
	return sum
}
