// A module the e2e checks add as a client, with constructor arguments a caller
// may omit. It is written in Go because the Go SDK hands a default value to the
// engine, which then declares the argument non-null with that default; a Dang
// module instead declares a defaulted argument nullable and applies the default
// itself.
package main

import "strings"

type ClientDefaults struct {
	Name   string
	Times  int
	Suffix string
}

func New(
	// +default="world"
	name string,
	// +default=1
	times int,
	// +optional
	suffix string,
) *ClientDefaults {
	return &ClientDefaults{Name: name, Times: times, Suffix: suffix}
}

func (m *ClientDefaults) Greeting() string {
	return strings.Repeat("hello ", m.Times) + m.Name + m.Suffix
}
