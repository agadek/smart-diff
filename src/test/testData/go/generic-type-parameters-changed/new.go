package num

func Sum[T ~int | ~int64 | ~float64](xs ...T) T {
	var total T
	for _, x := range xs {
		total += x
	}
	return total
}
