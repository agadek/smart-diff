package svc

func First(values []int) int {
	total := 0
	for _, v := range values {
		total += v
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
