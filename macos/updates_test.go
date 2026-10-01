package main

import "testing"

func TestNewer(t *testing.T) {
	for _, c := range []struct {
		v, current string
		want       bool
	}{
		{"0.2.4", "0.2.3", true},
		{"0.10.0", "0.9.9", true},
		{"1.0.0", "0.99.99", true},
		{"0.2.3", "0.2.3", false},
		{"0.2.2", "0.2.3", false},
		{"0.2.4", "0.2.3-dev", false}, // development builds are left alone
		{"0.2.4", "dev", false},
		{"v0.2.4", "0.2.3", false}, // callers strip the tag's v
	} {
		if got := newer(c.v, c.current); got != c.want {
			t.Errorf("newer(%q, %q) = %v, want %v", c.v, c.current, got, c.want)
		}
	}
}
