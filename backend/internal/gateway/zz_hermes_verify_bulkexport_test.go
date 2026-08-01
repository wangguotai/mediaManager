package gateway

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"media-manager/backend/internal/service"
	"media-manager/backend/internal/storage"
)

// TestHermesVerifyBulkExport is an AD-HOC verification of handleMediaBulkExport
// covering the 4-path matrix (405/401/503/200) and, under a seeded real store,
// the type-normalization / tag-intersection / date-range / taken_at branches.
// Temporary; delete before commit.
func TestHermesVerifyBulkExport(t *testing.T) {
	mkServer := func(t *testing.T, withStore bool) (*Server, *storage.Store) {
		t.Helper()
		root := filepath.Join(t.TempDir(), "users")
		dirs := service.NewUserDirs(root)
		svc := service.NewMediaService(dirs, "")
		srv := NewServer(":0", OpenClawConfig{}, svc, dirs, nil)
		var store *storage.Store
		if withStore {
			s, err := storage.Open(filepath.Join(t.TempDir(), "test.db"))
			if err != nil {
				t.Fatalf("storage.Open: %v", err)
			}
			store = s
			srv.SetStore(store)
		}
		return srv, store
	}

	// --- 405 method ---
	srv, store := mkServer(t, true)
	defer func() { _ = store.Close() }()
	{
		req := httptest.NewRequest(http.MethodPost, "/api/media/media-bulk-export", nil)
		req = req.WithContext(service.WithUserID(req.Context(), "u1"))
		rec := httptest.NewRecorder()
		srv.handleMediaBulkExport(rec, req)
		if rec.Code != http.StatusMethodNotAllowed {
			t.Fatalf("POST want 405, got %d body=%s", rec.Code, rec.Body.String())
		}
	}
	// --- 401 no uid ---
	{
		req := httptest.NewRequest(http.MethodGet, "/api/media/media-bulk-export", nil)
		rec := httptest.NewRecorder()
		srv.handleMediaBulkExport(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("no-uid want 401, got %d", rec.Code)
		}
	}

	// --- 503 store nil ---
	srv2, _ := mkServer(t, false)
	{
		req := httptest.NewRequest(http.MethodGet, "/api/media/media-bulk-export", nil)
		req = req.WithContext(service.WithUserID(req.Context(), "u1"))
		rec := httptest.NewRecorder()
		srv2.handleMediaBulkExport(rec, req)
		if rec.Code != http.StatusServiceUnavailable {
			t.Fatalf("nil-store want 503, got %d", rec.Code)
		}
	}

	// --- 200 + seeded branches ---
	srvS, storeS := mkServer(t, true)
	defer func() { _ = storeS.Close() }()
	ctx := context.Background()
	uid := "u-seed"
	if err := storeS.CreateUser(ctx, &storage.User{ID: uid, Username: "seed", PasswordHash: "x", Role: "user"}); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	now := time.Date(2026, 6, 15, 12, 0, 0, 0, time.UTC)
	// m1: IMAGE, tagged "trip", -30d, taken_at set
	// m2: VIDEO, tagged "trip", -10d, taken_at 0 (unknown)
	// m3: empty type (normalize→IMAGE), tagged "work", -2d, taken_at set
	// m4: IMAGE, no tag, -1d, taken_at 0
	// ListMediaByUser returns created_at DESC → order: m4(-1d), m3(-2d), m2(-10d), m1(-30d)
	seed := func(id, typ string, ca time.Time, takenAt int64) {
		if err := storeS.CreateMedia(ctx, &storage.Media{
			ID: id, UserID: uid, Type: typ, Size: 1024, Mime: "image/jpeg",
			Width: 100, Height: 100, SHA256: "sha_" + id, CreatedAt: ca, TakenAt: takenAt,
		}); err != nil {
			t.Fatalf("CreateMedia %s: %v", id, err)
		}
	}
	seed("m1", "IMAGE", now.Add(-30*24*time.Hour), 1700000000000)
	seed("m2", "VIDEO", now.Add(-10*24*time.Hour), 0)
	seed("m3", "", now.Add(-2*24*time.Hour), 1700000000001)
	seed("m4", "IMAGE", now.Add(-1*24*time.Hour), 0)
	if err := storeS.AddMediaTag(ctx, uid, "m1", "trip"); err != nil {
		t.Fatalf("tag m1: %v", err)
	}
	if err := storeS.AddMediaTag(ctx, uid, "m2", "trip"); err != nil {
		t.Fatalf("tag m2: %v", err)
	}
	if err := storeS.AddMediaTag(ctx, uid, "m3", "work"); err != nil {
		t.Fatalf("tag m3: %v", err)
	}

	get := func(q string) (int, map[string]any) {
		req := httptest.NewRequest(http.MethodGet, "/api/media/media-bulk-export"+q, nil)
		req = req.WithContext(service.WithUserID(req.Context(), uid))
		rec := httptest.NewRecorder()
		srvS.handleMediaBulkExport(rec, req)
		var body map[string]any
		if rec.Code == 200 {
			if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
				t.Fatalf("unmarshal: %v body=%s", err, rec.Body.String())
			}
		}
		return rec.Code, body
	}
	wantTotal := func(label string, q string, want float64) {
		code, b := get(q)
		if code != 200 {
			t.Fatalf("%s want 200, got %d", label, code)
		}
		if n, _ := b["total"].(float64); n != want {
			t.Fatalf("%s total want %.0f, got %v", label, want, b["total"])
		}
	}

	// 1) no filter: 4 items, exported_at present, DESC order, taken_at + normalize.
	{
		code, b := get("")
		if code != 200 {
			t.Fatalf("no-filter want 200, got %d", code)
		}
		if _, ok := b["exported_at"]; !ok {
			t.Fatalf("missing exported_at")
		}
		if n, _ := b["total"].(float64); n != 4 {
			t.Fatalf("no-filter total want 4, got %v", b["total"])
		}
		media, _ := b["media"].([]any)
		if len(media) != 4 {
			t.Fatalf("media len want 4, got %d", len(media))
		}
		m0 := media[0].(map[string]any)
		if m0["id"] != "m4" {
			t.Fatalf("DESC order: first want m4 (newest), got %v", m0["id"])
		}
		if m0["taken_at"].(float64) != 0 {
			t.Fatalf("m4 taken_at want 0, got %v", m0["taken_at"])
		}
		// m3 (-2d) is index 1; empty type normalized to IMAGE in the type field.
		m3 := media[1].(map[string]any)
		if m3["id"] != "m3" || m3["type"] != "IMAGE" {
			t.Fatalf("m3 normalize: want id=m3 type=IMAGE, got id=%v type=%v", m3["id"], m3["type"])
		}
	}

	// 2) type=VIDEO → only m2
	wantTotal("type=VIDEO", "?type=VIDEO", 1)
	// 3) type=IMAGE → m1, m3(normalized), m4
	wantTotal("type=IMAGE", "?type=IMAGE", 3)
	// 4) tag=trip → m1, m2
	wantTotal("tag=trip", "?tag=trip", 2)
	// 5) tag=work → only m3 (proves intersection, not all-tagged)
	wantTotal("tag=work", "?tag=work", 1)
	// 6) type=IMAGE & tag=trip → only m1 (m2 is VIDEO, excluded by type)
	wantTotal("type=IMAGE&tag=trip", "?type=IMAGE&tag=trip", 1)

	// 7) date_from >= now-5d → m3(-2d), m4(-1d)
	wantTotal("date_from-5d", "?date_from="+now.Add(-5*24*time.Hour).Format(time.RFC3339), 2)
	// 8) date_to <= now-20d → m1(-30d)
	wantTotal("date_to-20d", "?date_to="+now.Add(-20*24*time.Hour).Format(time.RFC3339), 1)
	// 9) illegal date silently ignored → 4
	wantTotal("bad-date-ignored", "?date_from=not-a-date", 4)

	// 10) taken_at + sha256 + created_at carried for m1: tag=trip & date_to<=now-25d → m1 only
	{
		_, b := get("?tag=trip&date_to=" + now.Add(-25*24*time.Hour).Format(time.RFC3339))
		media := b["media"].([]any)
		if len(media) != 1 {
			t.Fatalf("case10 len want 1, got %d", len(media))
		}
		m1 := media[0].(map[string]any)
		if m1["id"] != "m1" {
			t.Fatalf("case10 want m1, got %v", m1["id"])
		}
		if m1["taken_at"].(float64) != 1700000000000 {
			t.Fatalf("m1 taken_at want 1700000000000, got %v", m1["taken_at"])
		}
		if v, ok := m1["created_at"].(string); !ok || v == "" {
			t.Fatalf("m1 created_at missing/empty: %v", m1["created_at"])
		}
		if m1["sha256"] != "sha_m1" {
			t.Fatalf("m1 sha256 want sha_m1, got %v", m1["sha256"])
		}
	}
}
