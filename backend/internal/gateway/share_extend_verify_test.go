package gateway

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"testing"
	"time"

	"media-manager/backend/internal/storage"
)

// AD-HOC verification (not part of permanent suite) for new extend-share code.

func TestAdhocExtendRepoRules(t *testing.T) {
	srv, _, uid, _, _ := newShareGateway(t)
	store := srv.store
	ctx := context.Background()

	mk := func(id string, expiry time.Time) string {
		st := &storage.ShareToken{Token: "ext" + id, UserID: uid, MediaIDs: `["mid-share-1"]`}
		if !expiry.IsZero() {
			st.ExpiresAt = expiry
		}
		if err := store.CreateShareToken(ctx, st); err != nil {
			t.Fatalf("CreateShareToken %s: %v", id, err)
		}
		return st.Token
	}

	neverTok := mk("A", time.Time{})
	if err := store.ExtendShareToken(ctx, neverTok, uid, 24*time.Hour); err != nil {
		t.Fatalf("A never-expires extend: want nil, got %v", err)
	}
	if got, _ := store.GetShareToken(ctx, neverTok); !got.ExpiresAt.IsZero() {
		t.Fatalf("A: expires_at should remain zero, got %v", got.ExpiresAt)
	}

	future := time.Now().Add(48 * time.Hour)
	bTok := mk("B", future)
	if err := store.ExtendShareToken(ctx, bTok, uid, 24*time.Hour); err != nil {
		t.Fatalf("B extend: %v", err)
	}
	gotB, _ := store.GetShareToken(ctx, bTok)
	wantB := future.Add(24 * time.Hour)
	if diff := gotB.ExpiresAt.Sub(wantB); diff > time.Second || diff < -time.Second {
		t.Fatalf("B: expiry want ~%v, got %v (diff %v)", wantB, gotB.ExpiresAt, diff)
	}

	past := time.Now().Add(-2 * time.Hour)
	cTok := mk("C", past)
	before := time.Now()
	if err := store.ExtendShareToken(ctx, cTok, uid, 24*time.Hour); err != nil {
		t.Fatalf("C extend: %v", err)
	}
	gotC, _ := store.GetShareToken(ctx, cTok)
	lower := before.Add(24 * time.Hour)
	upper := time.Now().Add(24 * time.Hour)
	if gotC.ExpiresAt.Before(lower) || gotC.ExpiresAt.After(upper) {
		t.Fatalf("C: expiry want in [%v,%v], got %v", lower, upper, gotC.ExpiresAt)
	}

	dTok := mk("D", future)
	if err := store.ExtendShareToken(ctx, dTok, "someone-else", 24*time.Hour); !errors.Is(err, storage.ErrNotFound) {
		t.Fatalf("D wrong-owner: want ErrNotFound, got %v", err)
	}

	if err := store.ExtendShareToken(ctx, "nope-not-real", uid, 24*time.Hour); !errors.Is(err, storage.ErrNotFound) {
		t.Fatalf("E missing: want ErrNotFound, got %v", err)
	}

	if err := store.ExtendShareToken(ctx, bTok, "", 24*time.Hour); !errors.Is(err, storage.ErrNotFound) {
		t.Fatalf("F empty-uid: want ErrNotFound, got %v", err)
	}
	t.Logf("repo rules OK: A never-expire, B not-expired(+24h), C expired(reset), D wrong-owner, E missing, F empty-uid")
}

func TestAdhocExtendHandler(t *testing.T) {
	srv, aliceTok, uid, mids, _ := newShareGateway(t)
	ctx := context.Background()

	extTok := "ext-handler-1"
	seedExpiry := time.Now().Add(2 * time.Hour)
	if err := srv.store.CreateShareToken(ctx, &storage.ShareToken{
		Token: extTok, UserID: uid, MediaIDs: fmt.Sprintf("[%q]", mids[0]),
		ExpiresAt: seedExpiry,
	}); err != nil {
		t.Fatalf("seed: %v", err)
	}

	body := []byte(`{"extend_hours":24}`)
	code, m := doShare(t, srv, shareReq(http.MethodPost, "/api/share/extend?token="+extTok, aliceTok, body))
	if code != 200 {
		t.Fatalf("extend success: want 200, got %d body=%v", code, m)
	}
	if m["status"] != "success" {
		t.Fatalf("extend: status want success, got %v", m["status"])
	}
	nea, _ := m["new_expires_at"].(string)
	if nea == "" {
		t.Fatalf("extend: new_expires_at empty")
	}
	parsed, err := time.Parse(time.RFC3339, nea)
	if err != nil {
		t.Fatalf("extend: parse new_expires_at %q: %v", nea, err)
	}
	want := seedExpiry.Add(24 * time.Hour)
	if diff := parsed.Sub(want); diff > time.Second || diff < -time.Second {
		t.Fatalf("extend: new expiry want ~%v, got %v", want, parsed)
	}

	code, m = doShare(t, srv, shareReq(http.MethodPost, "/api/share/extend?token="+extTok, "", body))
	if code != 401 {
		t.Fatalf("no-auth: want 401, got %d body=%v", code, m)
	}

	code, m = doShare(t, srv, shareReq(http.MethodPost, "/api/share/extend", aliceTok, body))
	if code != 400 {
		t.Fatalf("missing token: want 400, got %d body=%v", code, m)
	}

	beforeExt, _ := srv.store.GetShareToken(ctx, extTok)
	code, m = doShare(t, srv, shareReq(http.MethodPost, "/api/share/extend?token="+extTok, aliceTok, nil))
	if code != 200 {
		t.Fatalf("default-hours: want 200, got %d body=%v", code, m)
	}
	afterExt, _ := srv.store.GetShareToken(ctx, extTok)
	if diff := afterExt.ExpiresAt.Sub(beforeExt.ExpiresAt); diff > (24*time.Hour+time.Second) || diff < (24*time.Hour-time.Second) {
		t.Fatalf("default-hours: want +24h, got diff %v", diff)
	}

	otherTok := "ext-other-1"
	otherUID := "u-bob-other"
	if err := srv.store.CreateUser(ctx, &storage.User{ID: otherUID, Username: "bobx", PasswordHash: "x"}); err != nil {
		t.Fatalf("create other user: %v", err)
	}
	if err := srv.store.CreateShareToken(ctx, &storage.ShareToken{
		Token: otherTok, UserID: otherUID, MediaIDs: `[]`,
		ExpiresAt: time.Now().Add(10 * time.Hour),
	}); err != nil {
		t.Fatalf("seed other: %v", err)
	}
	code, m = doShare(t, srv, shareReq(http.MethodPost, "/api/share/extend?token="+otherTok, aliceTok, body))
	if code != 404 {
		t.Fatalf("wrong-owner: want 404, got %d body=%v", code, m)
	}

	code, m = doShare(t, srv, shareReq(http.MethodGet, "/api/share/extend?token="+extTok, aliceTok, nil))
	if code != 405 {
		t.Fatalf("GET method: want 405, got %d body=%v", code, m)
	}
	t.Logf("handler OK: success, no-auth 401, missing-token 400, default-24h, wrong-owner 404, GET 405")
}
