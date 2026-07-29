// Package signaling 实现设备间的候选地址交换（信令介绍）。
//
// 信令模型：
//   - 直连场景下，两端各自把可达的候选地址（IP:port / reflexive / relay 候选）上报给运营服务端。
//   - 运营服务端作为"介绍人" (introducer)：当双方候选就绪，把对端候选集下发给彼此，
//     供其尝试直连 (P2P)。直连失败则回退到 relay（见 internal/relay）。
//   - 本包提供候选注册与配对介绍的同步逻辑，实时转发路径由 internal/ws 的 Hub 在 WS 连接上完成。
//
// 设计取舍：
//   - 候选地址缓存在内存（Signaler），存活期与一次配对尝试绑定；不落库（候选时效短、体积小）。
//   - 配对键 pairKey 形如 "<peerA>__<peerB>"，双方候选以 peerID 归集；peerID 由调用方约定
//     （通常 "<server_id>:<device_id>" 或纯 device_id）。
package signaling

import (
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"
)

// Candidate 一条候选地址。Addr 形如 "1.2.3.4:5678" 或 "stun:..."，Type 标注候选类型。
type Candidate struct {
	Type string `json:"type"` // host / srflx / prflx / relay
	Addr string `json:"addr"`
}

// PeerCandidates 某一端为某次配对上报的候选集合。
type PeerCandidates struct {
	PeerID     string      `json:"peer_id"`
	PairKey    string      `json:"pair_key"`
	Candidates []Candidate `json:"candidates"`
	UpdatedAt  time.Time   `json:"updated_at"`
}

// Introduction 是一次成功配对介绍的产物：双方候选拼合，供各自尝试直连。
type Introduction struct {
	PairKey   string         `json:"pair_key"`
	Local     PeerCandidates `json:"local"`  // A 端候选
	Remote    PeerCandidates `json:"remote"` // B 端候选
	CreatedAt time.Time      `json:"created_at"`
}

// ErrIncompletePair 双方候选尚未齐全，无法完成介绍。
var ErrIncompletePair = errors.New("pair incomplete: both peers must register candidates")

// Signaler 维护内存中的候选集合与配对介绍。并发安全。
// 候选过期清理由调用方按需触发（Reap 候选过旧）。
type Signaler struct {
	mu      sync.Mutex
	byPair  map[string]map[string]PeerCandidates // pairKey -> peerID -> candidates
	byPeer  map[string]string                    // peerID -> pairKey（最新归属）
	nowFunc func() time.Time
}

// New 构造一个 Signaler。
func New(nowFunc func() time.Time) *Signaler {
	if nowFunc == nil {
		nowFunc = time.Now
	}
	return &Signaler{
		byPair:  make(map[string]map[string]PeerCandidates),
		byPeer:  make(map[string]string),
		nowFunc: nowFunc,
	}
}

// RegisterCandidates 登记一端为某配对上报的候选地址。返回登记后的快照。
// 调用方应在 WS/REST 收到候选时报时调用，然后由 Hub 触发 Introduce 转发到对端。
func (s *Signaler) RegisterCandidates(peerID, pairKey string, cands []Candidate) PeerCandidates {
	if pairKey == "" {
		pairKey = defaultPairKey(peerID)
	}
	pc := PeerCandidates{
		PeerID:     peerID,
		PairKey:    pairKey,
		Candidates: dedupSorted(cands),
		UpdatedAt:  s.nowFunc(),
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.byPair[pairKey]
	if !ok {
		m = make(map[string]PeerCandidates)
		s.byPair[pairKey] = m
	}
	m[peerID] = pc
	// 迁移 peer 归属：若该 peer 之前挂在别的 pairKey 下，先从那里摘除，避免跨对残留。
	if old, ok := s.byPeer[peerID]; ok && old != pairKey {
		if om, ok2 := s.byPair[old]; ok2 {
			delete(om, peerID)
			if len(om) == 0 {
				delete(s.byPair, old)
			}
		}
	}
	s.byPeer[peerID] = pairKey
	return pc
}

// Introduce 尝试为指定两 peer 完成配对介绍。要求双方均已用相同 pairKey 登记候选。
// 返回 Introduction 供调用方（Hub/REST）下发给双方。双方不齐返回 ErrIncompletePair。
func (s *Signaler) Introduce(peerA, peerB, pairKey string) (Introduction, error) {
	if pairKey == "" {
		pairKey = pairKeyFor(peerA, peerB)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.byPair[pairKey]
	if !ok {
		return Introduction{}, ErrIncompletePair
	}
	a, okA := m[peerA]
	b, okB := m[peerB]
	if !okA || !okB {
		return Introduction{}, ErrIncompletePair
	}
	return Introduction{
		PairKey:   pairKey,
		Local:     a,
		Remote:    b,
		CreatedAt: s.nowFunc(),
	}, nil
}

// PickPartner 在已知 peerB 的 pairKey 时，取对端候选（用于单边"我要对端地址"查询）。
// 仅当对端已登记时返回；否则返回 ErrIncompletePair。
func (s *Signaler) PickPartner(peerB, pairKey string) (PeerCandidates, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	m, ok := s.byPair[pairKey]
	if !ok {
		return PeerCandidates{}, ErrIncompletePair
	}
	// 取 pairKey 下"非 peerB"的那个候选集。
	for id, pc := range m {
		if id != peerB {
			return pc, nil
		}
	}
	return PeerCandidates{}, ErrIncompletePair
}

// ClearPair 清除某配对的所有候选（直连已建立或放弃后调用，释放内存）。
func (s *Signaler) ClearPair(pairKey string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if m, ok := s.byPair[pairKey]; ok {
		for peerID := range m {
			if s.byPeer[peerID] == pairKey {
				delete(s.byPeer, peerID)
			}
		}
		delete(s.byPair, pairKey)
	}
}

// Reap 清理超过 maxAge 未更新的候选。返回清理的配对数。
func (s *Signaler) Reap(maxAge time.Duration) int {
	cutoff := s.nowFunc().Add(-maxAge)
	removed := 0
	s.mu.Lock()
	defer s.mu.Unlock()
	for pairKey, m := range s.byPair {
		for peerID, pc := range m {
			if pc.UpdatedAt.Before(cutoff) {
				delete(m, peerID)
				if s.byPeer[peerID] == pairKey {
					delete(s.byPeer, peerID)
				}
			}
		}
		if len(m) == 0 {
			delete(s.byPair, pairKey)
			removed++
		}
	}
	return removed
}

// defaultPairKey 单 peer 的默认配对键（仅登记、尚未对端时占位）。
func defaultPairKey(peerID string) string {
	return peerID
}

// pairKeyFor 由双方 peerID 派生稳定配对键（字典序拼接，保证双方调用结果一致）。
func pairKeyFor(a, b string) string {
	parts := []string{a, b}
	sort.Strings(parts)
	return strings.Join(parts, "__")
}

// dedupSorted 对候选按 (Type,Addr) 去重并稳定排序，避免重复候选干扰对端。
func dedupSorted(cands []Candidate) []Candidate {
	seen := make(map[string]struct{}, len(cands))
	out := make([]Candidate, 0, len(cands))
	for _, c := range cands {
		k := c.Type + "|" + c.Addr
		if _, ok := seen[k]; ok {
			continue
		}
		seen[k] = struct{}{}
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Type != out[j].Type {
			return out[i].Type < out[j].Type
		}
		return out[i].Addr < out[j].Addr
	})
	return out
}

// ParsePeerID 把 "<server_id>:<device_id>" 拼成的 peerID 拆回，供日志/记账用。
func ParsePeerID(peerID string) (serverID, deviceID string) {
	parts := strings.SplitN(peerID, ":", 2)
	if len(parts) == 2 {
		return parts[0], parts[1]
	}
	return "", peerID
}

// FormatPeerID 把 server_id + device_id 拼成稳定 peerID。
func FormatPeerID(serverID, deviceID string) string {
	return fmt.Sprintf("%s:%s", serverID, deviceID)
}
