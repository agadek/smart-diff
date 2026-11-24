package svc

func Sum(values []int) int {
	total := 0
	for _, v := range values {
		total += v * 1
	}
	return total
}

func Second(values []int) int {
	count := 0
	for range values {
		count++
	}
	return count
}
