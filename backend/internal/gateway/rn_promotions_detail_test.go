package gateway

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// ----- /api/promotions/challenge -----

// TestPromotionsChallengeRequiresNoAuth 验证 challenge 端点认证可选：
// 不注入 uid 的请求（模拟匿名）也应返回 200，current_count=0，rankings 为 5 条 mock。
func TestPromotionsChallengeRequiresNoAuth(t *testing.T) {
	srv, _ := newRNGateway(t)
	// 不用 rnReq（rnReq 会注入 uid），构造纯匿名请求。
	req := httptest.NewRequest(http.MethodGet, "/api/promotions/challenge", nil)
	code, body := doReq(t, srv, req)
	if code != http.StatusOK {
		t.Fatalf("challenge anonymous: want 200, got %d body=%s", code, body)
	}
	var resp challengeResponse
	if err := json.Unmarshal([]byte(body), &resp); err != nil {
		t.Fatalf("decode: %v body=%s", err, body)
	}
	if resp.Challenge.ID != "summer-2026" {
		t.Fatalf("challenge id=%s want summer-2026", resp.Challenge.ID)
	}
	if resp.Challenge.TargetCount != 10 {
		t.Fatalf("target_count=%d want 10", resp.Challenge.TargetCount)
	}
	if resp.Challenge.CurrentCount != 0 {
		t.Fatalf("anonymous current_count=%d want 0", resp.Challenge.CurrentCount)
	}
	if resp.Challenge.ProgressPct != 0 {
		t.Fatalf("anonymous progress_pct=%d want 0", resp.Challenge.ProgressPct)
	}
	if resp.Challenge.Participants != 128 {
		t.Fatalf("participants=%d want 128", resp.Challenge.Participants)
	}
	if len(resp.Rankings) != 5 {
		t.Fatalf("rankings len=%d want 5", len(resp.Rankings))
	}
	if resp.Rankings[0].Rank != 1 || resp.Rankings[0].Username != "alice" {
		t.Fatalf("top ranking=%+v want alice rank 1", resp.Rankings[0])
	}
}

// TestPromotionsChallengeMethodNotAllowed 验证非 GET 方法返回 405。
func TestPromotionsChallengeMethodNotAllowed(t *testing.T) {
	srv, _ := newRNGateway(t)
	req := httptest.NewRequest(http.MethodPost, "/api/promotions/challenge", nil)
	code, _ := doReq(t, srv, req)
	if code != http.StatusMethodNotAllowed {
		t.Fatalf("post challenge: want 405, got %d", code)
	}
}

// ----- /api/promotions/{id} -----

// TestPromotionDetailFound 验证按 id 命中返回完整详情（含 rules/participantsCount/createdAt）。
func TestPromotionDetailFound(t *testing.T) {
	srv, _ := newRNGateway(t)
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/promotions/p1"))
	if code != http.StatusOK {
		t.Fatalf("detail: want 200, got %d body=%s", code, body)
	}
	var d promotionDetail
	if err := json.Unmarshal([]byte(body), &d); err != nil {
		t.Fatalf("decode: %v body=%s", err, body)
	}
	if d.ID != "p1" {
		t.Fatalf("id=%s want p1", d.ID)
	}
	if d.Title != "夏季活动" {
		t.Fatalf("title=%s want 夏季活动", d.Title)
	}
	if d.Rules == "" {
		t.Fatalf("rules empty, want non-empty detail")
	}
	if d.ParticipantsCount == 0 {
		t.Fatalf("participantsCount=0, want non-zero")
	}
	if d.CreatedAt == "" {
		t.Fatalf("createdAt empty, want non-empty")
	}
}

// TestPromotionDetailNotFound 验证未命中 id 返回 404。
func TestPromotionDetailNotFound(t *testing.T) {
	srv, _ := newRNGateway(t)
	code, _ := doReq(t, srv, rnReq(http.MethodGet, "/api/promotions/nope"))
	if code != http.StatusNotFound {
		t.Fatalf("missing id: want 404, got %d", code)
	}
}

// TestPromotionDetailNoAuth 验证无 token 访问 /api/promotions/{id} 返回 401
// （与 challenge 的可选认证形成对比，detail 仍需认证）。
func TestPromotionDetailNoAuth(t *testing.T) {
	srv, _ := newRNGateway(t)
	req := httptest.NewRequest(http.MethodGet, "/api/promotions/p1", nil)
	code, _ := doReq(t, srv, req)
	if code != http.StatusUnauthorized {
		t.Fatalf("detail anonymous: want 401, got %d", code)
	}
}

// TestPromotionDetailChallengeNotTreatedAsId 验证 challenge 精确路由优先，
// 不会落到 detail handler 被当活动 id（detail handler 内对 id=="challenge" 也返回 404）。
// 此处用带 uid 的请求打 /api/promotions/challenge，确认走的是 challenge handler。
func TestPromotionDetailChallengeNotTreatedAsId(t *testing.T) {
	srv, _ := newRNGateway(t)
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/promotions/challenge"))
	if code != http.StatusOK {
		t.Fatalf("challenge route: want 200, got %d body=%s", code, body)
	}
	// 响应体应是 challengeResponse（含 challenge 节点），而非 404。
	var resp challengeResponse
	if err := json.Unmarshal([]byte(body), &resp); err != nil {
		t.Fatalf("decode as challengeResponse: %v body=%s", err, body)
	}
	if resp.Challenge.ID == "" {
		t.Fatalf("challenge.id empty, body=%s", body)
	}
}
