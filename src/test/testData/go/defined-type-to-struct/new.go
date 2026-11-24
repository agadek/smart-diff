package ids

type ID struct {
	value int64
}

func (id ID) String() string { return "id" }
