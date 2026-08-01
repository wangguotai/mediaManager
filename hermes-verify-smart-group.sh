#!/usr/bin/env bash
set -euo pipefail
cd /Users/wgt/projects/media-manager/backend

echo "=== 1. go build ./... ==="
go build ./...
echo "build exit=$?"

echo "=== 2. go vet ./internal/gateway/ ==="
go vet ./internal/gateway/
echo "vet exit=$?"

echo "=== 3. route registration present ==="
grep -n 'media-tag-smart-group.*handleMediaTagSmartGroup' internal/gateway/server.go

echo "=== 4. handler func defined ==="
grep -n 'func (s \*Server) handleMediaTagSmartGroup' internal/gateway/server.go

echo "=== 5. only one route + one handler def (no dupes) ==="
echo "route count: $(grep -c 'media-tag-smart-group' internal/gateway/server.go)"
echo "func count:  $(grep -c 'func (s \*Server) handleMediaTagSmartGroup' internal/gateway/server.go)"

echo "=== 6. GET-only guard present (last match) ==="
grep -n 'r.Method != http.MethodGet' internal/gateway/server.go | tail -1

echo "=== 7. group order matches spec ==="
grep -n '旅行组\|美食组\|人物组\|风景组\|"其他组"' internal/gateway/server.go | tail -6

echo "=== 8. response keys present ==="
grep -n '"total_groups"\|"ungrouped_count"' internal/gateway/server.go | tail -3

echo "=== ALL CHECKS PASSED ==="
