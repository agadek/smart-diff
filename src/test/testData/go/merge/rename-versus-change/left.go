package calc

func Sum(values []int) int {
	sum := 0
	for _, v := range values {
		sum += v
	}
	return sum
}
