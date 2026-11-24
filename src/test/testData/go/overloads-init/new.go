package setup

var registry = map[string]int{}

func init() {
	registry["a"] = 1
}

func init() {
	registry["b"] = 20
}

func init() {
	registry["c"] = 3
}
