package setup

var registry = map[string]int{}

func init() {
	registry["a"] = 10
}

func init() {
	registry["b"] = 20
}

func init() {
	registry["c"] = 30
}
