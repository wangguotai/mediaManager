// Package gateway - PRD-v12 §3.3 人物聚类（长相记忆）。
//
// 真正的人脸聚类需 facerec 人脸向量库。本文件实现 v1：基于已有 CLIP 整图向量的
// 相似度聚类——同一人在相似场景/服饰/构图的图会被聚到一起，用户可给 cluster 命名
// （"我"/"妈妈"），检索时按 cluster 过滤实现"找我的照片"。当后续接入 facerec 时，
// 改用 face_vector 重算即可，表结构与端点不变。
//
// 聚类算法：凝聚层次聚类（agglomerative）。
//   - 每个图初始自成一簇
//   - 找余弦最大的两簇合并（单链接 single-linkage）
//   - 阈值 simThreshold 以下停止（默认 0.82，CLIP 整图相似度经验值；
//     接人脸向量时应调高到 0.5~0.6，人脸空间远比整图区分度大）
package gateway

import (
	"context"
	"fmt"
	"sort"

	"media-manager/backend/internal/storage"
)

// clusterByEmbeddings 用凝聚层次聚类把 media 向量分簇。
// 返回 media_id -> clusterIndex。
// inheritName 按"成员投票"继承旧命名：统计新 cluster 各成员在 mediaToName 中的
// 名字出现次数，取最多者；无命中返回空串。比按单张代表图匹配稳健（代表图随 map
// 遍历顺序随机，易丢命名）。
func inheritName(members []string, mediaToName map[string]string) string {
	counts := map[string]int{}
	for _, mid := range members {
		if n, ok := mediaToName[mid]; ok && n != "" {
			counts[n]++
		}
	}
	if len(counts) == 0 {
		return ""
	}
	best := ""
	bestN := 0
	for n, c := range counts {
		if c > bestN {
			best, bestN = n, c
		}
	}
	return best
}

func clusterByEmbeddings(embeds map[string][]float32, simThreshold float32) map[string]int {
	if len(embeds) == 0 {
		return nil
	}
	ids := make([]string, 0, len(embeds))
	for id := range embeds {
		ids = append(ids, id)
	}
	sort.Strings(ids) // 稳定顺序
	// 初始每点一簇
	clusterOf := make([]int, len(ids))
	for i := range clusterOf {
		clusterOf[i] = i
	}
	// 预计算相似度矩阵
	n := len(ids)
	sim := make([][]float32, n)
	for i := 0; i < n; i++ {
		sim[i] = make([]float32, n)
		for j := 0; j < n; j++ {
			if i == j {
				sim[i][j] = 1
			} else {
				sim[i][j] = storage.Cosine(embeds[ids[i]], embeds[ids[j]])
			}
		}
	}
	// 簇间相似度 = 单链接（最大成对相似度）。迭代合并直到最大簇间相似度 < 阈值。
	for {
		// 找最大簇间相似度
		bestI, bestJ := -1, -1
		var bestSim float32 = simThreshold
		for i := 0; i < n; i++ {
			for j := i + 1; j < n; j++ {
				if clusterOf[i] == clusterOf[j] {
					continue
				}
				if sim[i][j] > bestSim {
					bestSim = sim[i][j]
					bestI, bestJ = i, j
				}
			}
		}
		if bestI < 0 {
			break
		}
		// 合并 bestJ 所属簇到 bestI 所属簇
		from, to := clusterOf[bestJ], clusterOf[bestI]
		for k := range clusterOf {
			if clusterOf[k] == from {
				clusterOf[k] = to
			}
		}
	}
	// 重编号为连续 index
	remap := map[int]int{}
	next := 0
	out := make(map[string]int, len(ids))
	for i, id := range ids {
		c := clusterOf[i]
		if _, ok := remap[c]; !ok {
			remap[c] = next
			next++
		}
		out[id] = remap[c]
	}
	return out
}

// clusterFacesByMedia 用真人脸向量做凝聚聚类，返回 mediaID -> clusterIndex。
// 每张脸是一个聚类点；一张图的多张脸(不同人)可能归不同簇。最终 map 以 media 为粒度
// （若 media 有脸在簇 A 与其他簇，取覆盖它的强簇；简化：任一簇命中即标记，由后续
// 去重）。阈值 faceThr 建议 0.5（人脸空间余弦，比整图 0.7 区分度更高）。无人脸返回 nil。
func clusterFacesByMedia(faces []storage.FaceRec, faceThr float32) map[string]int {
	if len(faces) == 0 {
		return nil
	}
	// 每张脸一个点
	type pt struct{ media string; vec []float32 }
	pts := make([]pt, 0, len(faces))
	for _, f := range faces {
		if len(f.FaceVector) > 0 {
			pts = append(pts, pt{f.MediaID, f.FaceVector})
		}
	}
	if len(pts) < 2 {
		return nil
	}
	n := len(pts)
	parent := make([]int, n)
	for i := range parent {
		parent[i] = i
	}
	// 凝聚: 合并同簇内两点的最大相似度>=thr
	sim := make([][]float32, n)
	for i := 0; i < n; i++ {
		sim[i] = make([]float32, n)
		for j := 0; j < n; j++ {
			if i == j {
				sim[i][j] = 1
			} else {
				sim[i][j] = storage.Cosine(pts[i].vec, pts[j].vec)
			}
		}
	}
	for {
		bestI, bestJ := -1, -1
		var best float32 = faceThr
		for i := 0; i < n; i++ {
			for j := i + 1; j < n; j++ {
				if parent[i] == parent[j] {
					continue
				}
				if sim[i][j] > best {
					best, bestI, bestJ = sim[i][j], i, j
				}
			}
		}
		if bestI < 0 {
			break
		}
		from, to := parent[bestJ], parent[bestI]
		for k := range parent {
			if parent[k] == from {
				parent[k] = to
			}
		}
	}
	// 重编号
	remap := map[int]int{}
	next := 0
	for i := range pts {
		c := parent[i]
		if _, ok := remap[c]; !ok {
			remap[c] = next
			next++
		}
	}
	out := map[string]int{}
	for i, p := range pts {
		out[p.media] = remap[parent[i]]
	}
	return out
}

// ReclusterPersons 为某用户重算人物聚类。
// 流程：读全部图像向量 → 层次聚类 → 为每簇建/复用 person_cluster → 写 media_persons。
// 已有用户命名的 cluster 尽量保留（按代表 media 匹配）。
func (s *Server) ReclusterPersons(ctx context.Context, userID string, simThreshold float32) (int, error) {
	if s.store == nil {
		return 0, nil
	}
	embeds, err := s.store.LoadEmbeddings(ctx, userID)
	if err != nil {
		return 0, err
	}
	// 真人脸聚类优先（PRD §3.3 长相记忆）：若用户有人脸向量则按脸聚类（更准），
	// 否则回退 CLIP 整图向量（近似）。脸阈值 0.5（人脸空间区分度>整图）。
	faces, _ := s.store.ListAllFaceVectors(ctx, userID)
	var clusters map[string]int
	hasFace := len(faces) >= 2
	if hasFace {
		clusters = clusterFacesByMedia(faces, 0.5)
		if clusters == nil {
			hasFace = false
		}
	}
	if !hasFace {
		if len(embeds) < 2 {
			return 0, nil
		}
		clusters = clusterByEmbeddings(embeds, simThreshold)
	}
	// 收集每簇成员
	clusterMembers := map[int][]string{}
	for mid, c := range clusters {
		clusterMembers[c] = append(clusterMembers[c], mid)
	}

	// 保留旧命名：读旧 clusters 及其成员，建 mediaID→name 映射。
	// 重建后新 cluster 任一成员曾在旧命名 cluster 中即继承该名（按成员多数继承，
	// 避免旧版按单张代表图匹配因 map 遍历顺序随机而丢失命名）。
	oldClusters, _ := s.store.ListPersonClusters(ctx, userID)
	mediaToName := map[string]string{} // mediaID -> name（来自旧命名 cluster 的成员）
	for _, oc := range oldClusters {
		if oc.Name == "" {
			continue
		}
		mids, _ := s.store.ListMediaByCluster(ctx, userID, oc.ID)
		for _, mid := range mids {
			mediaToName[mid] = oc.Name
		}
	}

	// 重建前必须清空旧 cluster/media_persons，否则每次 recluster 累积翻倍
	// （v3 修 bug：旧版只 Create 不清，cluster 数无限增长）。
	if err := s.store.ClearPersonsForUser(ctx, userID); err != nil {
		return 0, fmt.Errorf("clear old persons: %w", err)
	}

	created := 0
	for _, members := range clusterMembers {
		if len(members) == 0 {
			continue
		}
		avatar := members[0]
		// 命名继承：统计新 cluster 成员在旧命名映射中的名字，取出现最多者
		name := inheritName(members, mediaToName)
		pc, err := s.store.CreatePersonCluster(ctx, userID, name, avatar)
		if err != nil || pc == nil {
			continue
		}
		created++
		for _, mid := range members {
			_ = s.store.AddMediaPerson(ctx, &storage.MediaPerson{
				MediaID:   mid,
				UserID:    userID,
				ClusterID: pc.ID,
			})
		}
	}
	return created, nil
}
