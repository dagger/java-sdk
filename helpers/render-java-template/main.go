package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/iancoleman/strcase"
)

// render-java-template NAME TEMPLATE_DIR OUT_DIR
//
// Substitutes the Dagger module name into both file paths and file contents,
// matching the placeholders used by the default Java template:
//
//	dagger-module-placeholder -> kebab(name)   (artifactId / name)
//	daggermoduleplaceholder   -> pkg(name)      (package segment)
//	DaggerModule              -> camel(name)    (class name + file name)
//
// pkg(name) lowercases name and drops "-" and "_", so it is a valid single
// Java package segment (e.g. "my-module" -> "mymodule").
func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(args []string) error {
	if len(args) != 3 {
		return fmt.Errorf("usage: render-java-template NAME TEMPLATE_DIR OUT_DIR")
	}
	name, templateDir, outDir := args[0], args[1], args[2]
	pkg := strings.NewReplacer("-", "", "_", "").Replace(strings.ToLower(name))
	repl := strings.NewReplacer(
		"dagger-module-placeholder", strcase.ToKebab(name),
		"daggermoduleplaceholder", pkg,
		"DaggerModule", strcase.ToCamel(name),
	)
	return filepath.WalkDir(templateDir, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(templateDir, path)
		if err != nil {
			return err
		}
		if rel == "." {
			return nil
		}
		dst := filepath.Join(outDir, repl.Replace(rel))
		if entry.IsDir() {
			return os.MkdirAll(dst, 0o755)
		}
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			return err
		}
		content, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		return os.WriteFile(dst, []byte(repl.Replace(string(content))), 0o644)
	})
}
