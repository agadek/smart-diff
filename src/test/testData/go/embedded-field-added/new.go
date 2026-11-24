package srv

import "sync"

type Server struct {
	sync.Mutex
	Name string
}
