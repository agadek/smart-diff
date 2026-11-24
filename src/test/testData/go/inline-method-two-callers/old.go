package main

import "fmt"

func a() { helper() }

func b() { helper() }

func helper() {
	x := 1
	y := 2
	fmt.Println(x + y)
}
