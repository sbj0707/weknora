//go:build ignore

// inventory_ast inspects router syntax using only the Go standard library.
// It interprets registration order, not business code or conditional values.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/printer"
	"go/token"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

type group struct {
	Path       string
	Middleware []string
	Policy     string
}
type value struct {
	Text  string
	Group *group
	Items []value
}
type env map[string]value
type route struct {
	Method       string   `json:"method"`
	Path         string   `json:"path"`
	Handler      string   `json:"handler"`
	Source       string   `json:"source"`
	Line         int      `json:"line"`
	Function     string   `json:"function"`
	Middleware   []string `json:"middleware"`
	APIKeyPolicy string   `json:"api_key_policy"`
	Conditions   []string `json:"conditions"`
}
type site struct {
	Source     string `json:"source"`
	Line       int    `json:"line"`
	Function   string `json:"function"`
	Expression string `json:"expression"`
}
type extractor struct {
	root       string
	fs         *token.FileSet
	funcs      map[string]*ast.FuncDecl
	constants  map[string]value
	routes     []route
	candidates map[token.Pos]site
	processed  map[token.Pos]bool
	unresolved []site
	active     map[string]bool
	noAuth     map[string][]string
}

func (x *extractor) text(n ast.Node) string {
	var b bytes.Buffer
	_ = printer.Fprint(&b, x.fs, n)
	return b.String()
}
func copyEnv(e env) env {
	out := env{}
	for k, v := range e {
		out[k] = v
	}
	return out
}
func (x *extractor) site(n ast.Node, fn string) site {
	p := x.fs.Position(n.Pos())
	rel, _ := filepath.Rel(x.root, p.Filename)
	return site{filepath.ToSlash(rel), p.Line, fn, x.text(n)}
}
func (x *extractor) expr(e ast.Expr, vars env) value {
	switch n := e.(type) {
	case *ast.BasicLit:
		if n.Kind == token.STRING {
			s, _ := strconv.Unquote(n.Value)
			return value{Text: s}
		}
	case *ast.Ident:
		if v, ok := vars[n.Name]; ok {
			return v
		}
		if v, ok := x.constants[n.Name]; ok {
			return v
		}
		return value{Text: n.Name}
	case *ast.SelectorExpr:
		base := x.expr(n.X, vars).Text
		key := base + "." + n.Sel.Name
		if v, ok := x.constants[key]; ok {
			return v
		}
		if base == "http" && strings.HasPrefix(n.Sel.Name, "Method") {
			return value{Text: strings.ToUpper(strings.TrimPrefix(n.Sel.Name, "Method"))}
		}
		return value{Text: key}
	case *ast.BinaryExpr:
		if n.Op == token.ADD {
			return value{Text: x.expr(n.X, vars).Text + x.expr(n.Y, vars).Text}
		}
	case *ast.CompositeLit:
		v := value{Text: x.text(e)}
		for _, el := range n.Elts {
			if a, ok := el.(ast.Expr); ok {
				v.Items = append(v.Items, x.expr(a, vars))
			}
		}
		return v
	case *ast.CallExpr:
		if id, ok := n.Fun.(*ast.Ident); ok && id.Name == "newRBACGuards" {
			return value{Text: "rbacGuards"}
		}
		if s, ok := n.Fun.(*ast.SelectorExpr); ok {
			if s.Sel.Name == "New" && x.expr(s.X, vars).Text == "gin" {
				return value{Text: "router", Group: &group{Path: "/"}}
			}
			if s.Sel.Name == "Group" && len(n.Args) > 0 {
				p := x.expr(s.X, vars)
				if p.Group != nil {
					g := *p.Group
					g.Path = join(g.Path, x.expr(n.Args[0], vars).Text)
					g.Middleware = append([]string{}, p.Group.Middleware...)
					for _, a := range n.Args[1:] {
						g.Middleware = append(g.Middleware, x.expr(a, vars).Text)
					}
					return value{Group: &g}
				}
			}
			if s.Sel.Name == "apiKeyGroup" && len(n.Args) > 1 {
				p := x.expr(n.Args[0], vars)
				if p.Group != nil {
					g := *p.Group
					g.Policy = x.expr(n.Args[1], vars).Text
					return value{Group: &g}
				}
			}
			if s.Sel.Name == "With" && len(n.Args) > 0 {
				p := x.expr(s.X, vars)
				if p.Group != nil {
					g := *p.Group
					g.Policy = x.expr(n.Args[0], vars).Text
					return value{Group: &g}
				}
			}
		}
		args := []string{}
		for _, a := range n.Args {
			args = append(args, x.expr(a, vars).Text)
		}
		return value{Text: x.expr(n.Fun, vars).Text + "(" + strings.Join(args, ", ") + ")"}
	case *ast.FuncLit:
		p := x.fs.Position(n.Pos())
		return value{Text: fmt.Sprintf("inline@%s:%d", filepath.Base(p.Filename), p.Line)}
	}
	return value{Text: x.text(e)}
}
func join(base, rel string) string {
	p := path.Join(base, rel)
	if strings.HasSuffix(rel, "/") && !strings.HasSuffix(p, "/") {
		p += "/"
	}
	return p
}
func (x *extractor) invoke(name string, args []value, conditions []string) {
	f, ok := x.funcs[name]
	if !ok || x.active[name] || f.Body == nil {
		return
	}
	x.active[name] = true
	defer delete(x.active, name)
	vars := env{}
	i := 0
	for _, field := range f.Type.Params.List {
		for _, n := range field.Names {
			if i < len(args) {
				vars[n.Name] = args[i]
			} else {
				vars[n.Name] = value{Text: n.Name}
			}
			i++
		}
	}
	x.block(f.Body.List, vars, conditions, name)
}
func (x *extractor) record(c *ast.CallExpr, fn string, vars env, conditions []string, g *group, methods []string, rel string, handlers []ast.Expr, policy string) {
	x.processed[c.Pos()] = true
	if g == nil || !strings.HasPrefix(rel, "/") && rel != "" && rel != "/" {
		x.unresolved = append(x.unresolved, x.site(c, fn))
		return
	}
	s := x.site(c, fn)
	mw := append([]string{}, g.Middleware...)
	handler := ""
	for i, a := range handlers {
		t := x.expr(a, vars).Text
		if i == len(handlers)-1 {
			handler = t
		} else {
			mw = append(mw, t)
		}
	}
	if policy == "" {
		policy = g.Policy
	}
	for _, method := range methods {
		if !isMethod(method) {
			x.unresolved = append(x.unresolved, s)
			continue
		}
		x.routes = append(x.routes, route{method, join(g.Path, rel), handler, s.Source, s.Line, fn, append([]string{}, mw...), policy, append([]string{}, conditions...)})
	}
}
func (x *extractor) call(c *ast.CallExpr, vars env, conditions []string, fn string) {
	if s, ok := c.Fun.(*ast.SelectorExpr); ok {
		name := s.Sel.Name
		p := x.expr(s.X, vars)
		if name == "Use" && p.Group != nil {
			for _, a := range c.Args {
				middleware := x.expr(a, vars).Text
				if len(conditions) > 0 {
					middleware = "conditional [" + strings.Join(conditions, " && ") + "]: " + middleware
				}
				p.Group.Middleware = append(p.Group.Middleware, middleware)
			}
			return
		}
		if name == "apiKeyRoute" && len(c.Args) >= 4 {
			x.record(c, fn, vars, conditions, x.expr(c.Args[0], vars).Group, []string{x.expr(c.Args[1], vars).Text}, x.expr(c.Args[2], vars).Text, c.Args[4:], x.expr(c.Args[3], vars).Text)
			return
		}
		if isMethod(name) && len(c.Args) > 0 {
			x.record(c, fn, vars, conditions, p.Group, []string{name}, x.expr(c.Args[0], vars).Text, c.Args[1:], "")
			return
		}
		if name == "Any" && len(c.Args) > 0 {
			x.record(c, fn, vars, conditions, p.Group, []string{"GET", "POST", "PUT", "PATCH", "HEAD", "OPTIONS", "DELETE", "CONNECT", "TRACE"}, x.expr(c.Args[0], vars).Text, c.Args[1:], "")
			return
		}
		if name == "Handle" && len(c.Args) > 1 {
			x.record(c, fn, vars, conditions, p.Group, []string{x.expr(c.Args[0], vars).Text}, x.expr(c.Args[1], vars).Text, c.Args[2:], "")
			return
		}
	}
	if id, ok := c.Fun.(*ast.Ident); ok {
		if _, exists := x.funcs[id.Name]; exists {
			args := []value{}
			for _, a := range c.Args {
				args = append(args, x.expr(a, vars))
			}
			hasGroup := false
			for _, a := range args {
				if a.Group != nil {
					hasGroup = true
				}
			}
			if hasGroup {
				x.invoke(id.Name, args, conditions)
			}
		}
	}
}
func returnOnly(b *ast.BlockStmt) bool {
	if b == nil {
		return false
	}
	for _, s := range b.List {
		switch s.(type) {
		case *ast.ReturnStmt:
			return true
		case *ast.ExprStmt:
		default:
			return false
		}
	}
	return false
}
func (x *extractor) block(stmts []ast.Stmt, vars env, conditions []string, fn string) {
	conds := append([]string{}, conditions...)
	for _, stmt := range stmts {
		switch n := stmt.(type) {
		case *ast.AssignStmt:
			for i, l := range n.Lhs {
				if id, ok := l.(*ast.Ident); ok && i < len(n.Rhs) {
					vars[id.Name] = x.expr(n.Rhs[i], vars)
				}
			}
		case *ast.DeclStmt:
			if d, ok := n.Decl.(*ast.GenDecl); ok {
				for _, s := range d.Specs {
					if v, ok := s.(*ast.ValueSpec); ok {
						for i, name := range v.Names {
							if i < len(v.Values) {
								vars[name.Name] = x.expr(v.Values[i], vars)
							}
						}
					}
				}
			}
		case *ast.ExprStmt:
			if c, ok := n.X.(*ast.CallExpr); ok {
				x.call(c, vars, conds, fn)
			}
		case *ast.BlockStmt:
			x.block(n.List, copyEnv(vars), conds, fn)
		case *ast.IfStmt:
			if n.Init != nil {
				x.block([]ast.Stmt{n.Init}, vars, conds, fn)
			}
			condition := x.expr(n.Cond, vars).Text
			if returnOnly(n.Body) && n.Else == nil {
				conds = append(conds, "!("+condition+")")
				continue
			}
			x.block(n.Body.List, copyEnv(vars), append(append([]string{}, conds...), condition), fn)
			if n.Else != nil {
				x.block([]ast.Stmt{n.Else}, copyEnv(vars), append(append([]string{}, conds...), "!("+condition+")"), fn)
			}
		case *ast.RangeStmt:
			v := x.expr(n.X, vars)
			if len(v.Items) > 0 {
				for _, item := range v.Items {
					local := copyEnv(vars)
					if id, ok := n.Value.(*ast.Ident); ok {
						local[id.Name] = item
					}
					x.block(n.Body.List, local, conds, fn)
				}
			} else {
				x.block(n.Body.List, copyEnv(vars), append(append([]string{}, conds...), "dynamic range: "+x.text(n.X)), fn)
			}
		case *ast.ForStmt:
			x.block(n.Body.List, copyEnv(vars), append(append([]string{}, conds...), "dynamic for: "+x.text(n.Cond)), fn)
		}
	}
}
func isMethod(s string) bool {
	switch s {
	case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE":
		return true
	}
	return false
}
func main() {
	root := "."
	if len(os.Args) > 1 {
		root = os.Args[1]
	}
	root, _ = filepath.Abs(root)
	x := &extractor{root: root, fs: token.NewFileSet(), funcs: map[string]*ast.FuncDecl{}, constants: map[string]value{}, candidates: map[token.Pos]site{}, processed: map[token.Pos]bool{}, active: map[string]bool{}, noAuth: map[string][]string{}}
	files, err := filepath.Glob(filepath.Join(root, "internal/router/*.go"))
	if err != nil {
		panic(err)
	}
	for _, p := range files {
		if strings.HasSuffix(p, "_test.go") {
			continue
		}
		f, err := parser.ParseFile(x.fs, p, nil, 0)
		if err != nil {
			panic(err)
		}
		for _, decl := range f.Decls {
			if fn, ok := decl.(*ast.FuncDecl); ok && fn.Recv == nil {
				x.funcs[fn.Name.Name] = fn
				ast.Inspect(fn.Body, func(n ast.Node) bool {
					if c, ok := n.(*ast.CallExpr); ok {
						if s, ok := c.Fun.(*ast.SelectorExpr); ok && (isMethod(s.Sel.Name) || s.Sel.Name == "Any" || s.Sel.Name == "Handle" || s.Sel.Name == "apiKeyRoute") {
							if fn.Name.Name != "apiKeyRoute" {
								x.candidates[c.Pos()] = x.site(c, fn.Name.Name)
							}
						}
					}
					return true
				})
			}
		}
	}
	// Resolve exported handler constants, including MCP's path prefix.
	hs, _ := filepath.Glob(filepath.Join(root, "internal/handler/*.go"))
	for _, p := range hs {
		if strings.HasSuffix(p, "_test.go") {
			continue
		}
		f, e := parser.ParseFile(x.fs, p, nil, 0)
		if e != nil {
			panic(e)
		}
		for _, d := range f.Decls {
			if gd, ok := d.(*ast.GenDecl); ok && gd.Tok == token.CONST {
				for _, s := range gd.Specs {
					if vs, ok := s.(*ast.ValueSpec); ok {
						for i, n := range vs.Names {
							if i < len(vs.Values) {
								x.constants["handler."+n.Name] = x.expr(vs.Values[i], env{})
							}
						}
					}
				}
			}
		}
	}
	f, e := parser.ParseFile(x.fs, filepath.Join(root, "internal/middleware/auth.go"), nil, 0)
	if e != nil {
		panic(e)
	}
	for _, d := range f.Decls {
		if gd, ok := d.(*ast.GenDecl); ok {
			for _, s := range gd.Specs {
				if vs, ok := s.(*ast.ValueSpec); ok {
					for i, n := range vs.Names {
						if n.Name == "noAuthAPI" && i < len(vs.Values) {
							if cl, ok := vs.Values[i].(*ast.CompositeLit); ok {
								for _, el := range cl.Elts {
									if kv, ok := el.(*ast.KeyValueExpr); ok {
										p := x.expr(kv.Key, env{}).Text
										for _, m := range x.expr(kv.Value, env{}).Items {
											x.noAuth[p] = append(x.noAuth[p], m.Text)
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}
	x.invoke("NewRouter", []value{{Text: "params"}}, nil)
	unvisited := []site{}
	for pos, s := range x.candidates {
		if !x.processed[pos] {
			unvisited = append(unvisited, s)
		}
	}
	sort.Slice(unvisited, func(i, j int) bool {
		if unvisited[i].Source == unvisited[j].Source {
			return unvisited[i].Line < unvisited[j].Line
		}
		return unvisited[i].Source < unvisited[j].Source
	})
	sort.Slice(x.routes, func(i, j int) bool {
		if x.routes[i].Path == x.routes[j].Path {
			return x.routes[i].Method < x.routes[j].Method
		}
		return x.routes[i].Path < x.routes[j].Path
	})
	out := map[string]any{"routes": x.routes, "unresolved": x.unresolved, "unvisited_registration_sites": unvisited, "candidate_registration_sites": len(x.candidates), "visited_registration_sites": len(x.processed), "no_auth_allowlist": x.noAuth}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(out); err != nil {
		panic(err)
	}
}
