package main

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestRootRelayMirrorsMatchCanonical(t *testing.T) {
	names := []string{"main.go", "server.go", "protocol.go", "room.go"}
	for _, name := range names {
		t.Run(name, func(t *testing.T) {
			canonical, err := os.ReadFile(name)
			if err != nil {
				t.Fatalf("read canonical %s: %v", name, err)
			}
			mirror, err := os.ReadFile(filepath.Join("..", name))
			if err != nil {
				t.Fatalf("read root mirror %s: %v", name, err)
			}
			if !bytes.Equal(canonical, mirror) {
				t.Fatalf("root mirror %s differs from relay/%s", name, name)
			}
		})
	}
}
