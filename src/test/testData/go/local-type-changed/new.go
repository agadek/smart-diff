package main

func main() {
	type pair struct {
		a, b int64
	}
	p := pair{1, 2}
	println(p.a)
}
