package svc

func Second(values []int) int {
	count := 0
	for range values {
		count++
	}
	return count
}

func First(values []int) int {
	total := 0
	for _, v := range values {
		total += v
	}
	return total
}
