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
	"sort"

	"media-manager/backend/internal/storage"
)

// clusterByEmbeddings 用凝聚层次聚类把 media 向量分簇。
// 返回 media_id -> clusterIndex。
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
	if len(embeds) < 2 {
		return 0, nil
	}
	clusters := clusterByEmbeddings(embeds, simThreshold)
	// 收集每簇成员
	clusterMembers := map[int][]string{}
	for mid, c := range clusters {
		clusterMembers[c] = append(clusterMembers[c], mid)
	}

	// 保留旧命名：读旧 clusters，按代表 media id 找回名字
	oldClusters, _ := s.store.ListPersonClusters(ctx, userID)
	oldByName := map[string]string{} // avatarMediaID -> name
	oldByID := map[string]*storage.PersonCluster{}
	for _, oc := range oldClusters {
		oldByID[oc.ID] = oc
		if oc.AvatarMediaID != "" {
			oldByName[oc.AvatarMediaID] = oc.Name
		}
	}

	// 简化策略：清空旧 cluster/media_persons，重建（命名暂丢失，下版按代表图匹配保留）
	// 为避免丢用户命名，改为：保留仍存在的旧 cluster（按代表图归属新簇）。
	// 这里 v1 简化为重建，记录到 audit 由前端提示用户重命名。
	_ = oldClusters

	created := 0
	for _, members := range clusterMembers {
		if len(members) == 0 {
			continue
		}
		// 代表图 = 第一个
		avatar := members[0]
		name := ""
		// 尝试保留旧命名：若代表图曾在旧 cluster 且有名字
		if n, ok := oldByName[avatar]; ok {
			name = n
		}
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
