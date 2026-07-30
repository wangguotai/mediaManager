/**
 * Media Manager RN 动态模块入口（V7 §3.1）
 *
 * 注册一个名为 "MediaManagerApp" 的 RN 组件，供 Android ReactHost 加载。
 * 当前的页面功能：活动中心——展示运营活动列表 + 媒体统计概览。
 */
import React, {useState, useEffect} from 'react';
import {
  AppRegistry,
  View,
  Text,
  ScrollView,
  StyleSheet,
  SafeAreaView,
  ActivityIndicator,
  Platform,
} from 'react-native';

// ─── 样式 ───
const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  header: {
    backgroundColor: '#6750A4',
    paddingVertical: 16,
    paddingHorizontal: 20,
    alignItems: 'center',
  },
  headerTitle: {
    color: '#fff',
    fontSize: 20,
    fontWeight: 'bold',
  },
  headerSubtitle: {
    color: '#fff',
    fontSize: 13,
    opacity: 0.8,
    marginTop: 4,
  },
  card: {
    backgroundColor: '#fff',
    marginHorizontal: 16,
    marginVertical: 8,
    borderRadius: 12,
    padding: 16,
    elev: 2,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#1c1b1f',
    marginBottom: 8,
  },
  cardText: {
    fontSize: 14,
    color: '#49454f',
    lineHeight: 20,
  },
  cardMeta: {
    fontSize: 12,
    color: '#79747e',
    marginTop: 8,
  },
  loading: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingTop: 40,
  },
  statRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingVertical: 12,
  },
  statItem: {
    alignItems: 'center',
  },
  statValue: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#6750A4',
  },
  statLabel: {
    fontSize: 12,
    color: '#79747e',
    marginTop: 4,
  },
  badge: {
    backgroundColor: '#FFD8E4',
    color: '#7D5260',
    fontSize: 12,
    fontWeight: 'bold',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
    alignSelf: 'flex-start',
    marginBottom: 6,
  },
});

// ─── 主组件 ───
function MediaManagerApp() {
  const [promotions, setPromotions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [stats, setStats] = useState({count: 0, size: '0 MB'});

  useEffect(() => {
    // 拉取运营活动列表（V7 §3.3）
    // 后端地址由 Compose 层注入的全局变量 nativeBackendUrl 提供（默认局域网）
    const baseUrl = global.nativeBackendUrl || 'http://192.168.31.251:8080';
    const token = global.nativeAuthToken || '';

    // 并行拉取 promotions + healthz（媒体统计）
    Promise.all([
      fetch(`${baseUrl}/api/promotions`, {
        headers: token ? {Authorization: `Bearer ${token}`} : {},
      }).then(r => r.json()).catch(() => []),
      fetch(`${baseUrl}/healthz`).then(r => r.json()).catch(() => ({})),
    ])
      .then(([promoData, healthData]) => {
        setPromotions(Array.isArray(promoData) ? promoData : []);
        setStats({
          count: healthData.media_count || 0,
          size: healthData.disk
            ? `${(parseFloat(healthData.disk.used_gb)).toFixed(1)} GB`
            : '0 MB',
        });
        setLoading(false);
      })
      .catch(() => {
        setLoading(false);
      });
  }, []);

  if (loading) {
    return (
      <SafeAreaView style={styles.container}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>活动中心</Text>
          <Text style={styles.headerSubtitle}>React Native 动态模块</Text>
        </View>
        <View style={styles.loading}>
          <ActivityIndicator size="large" color="#6750A4" />
          <Text style={{marginTop: 12, color: '#79747e'}}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView>
        {/* 头部 */}
        <View style={styles.header}>
          <Text style={styles.headerTitle}>活动中心</Text>
          <Text style={styles.headerSubtitle}>
            React Native 动态模块 · v1.0.0
          </Text>
        </View>

        {/* 媒体概览 */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>媒体概览</Text>
          <View style={styles.statRow}>
            <View style={styles.statItem}>
              <Text style={styles.statValue}>{stats.count}</Text>
              <Text style={styles.statLabel}>媒体数量</Text>
            </View>
            <View style={styles.statItem}>
              <Text style={styles.statValue}>{stats.size}</Text>
              <Text style={styles.statLabel}>已用空间</Text>
            </View>
          </View>
        </View>

        {/* 运营活动列表 */}
        {promotions.length > 0 ? (
          promotions.map((promo, index) => (
            <View key={promo.id || index} style={styles.card}>
              <Text style={styles.badge}>活动</Text>
              <Text style={styles.cardTitle}>{promo.title || '未知活动'}</Text>
              {promo.imageUrl ? (
                <Text style={styles.cardMeta}>支持图片: {promo.imageUrl}</Text>
              ) : null}
              {promo.link ? (
                <Text style={styles.cardMeta}>链接: {promo.link}</Text>
              ) : null}
              {promo.expiresAt ? (
                <Text style={styles.cardMeta}>
                  到期时间: {promo.expiresAt}
                </Text>
              ) : null}
            </View>
          ))
        ) : (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>暂无活动</Text>
            <Text style={styles.cardText}>
              目前没有运营活动。请稍后再来查看。
            </Text>
          </View>
        )}

        {/* 关于 */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>关于本模块</Text>
          <Text style={styles.cardText}>
            此页面由 React Native 渲染，通过后端动态下发 bundle 加载。
            支持热更新——后端更新 bundle 后客户端自动拉取最新版本。
          </Text>
          <Text style={styles.cardMeta}>
            Platform: {Platform.OS} · React Native 0.84.1
          </Text>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

AppRegistry.registerComponent('MediaManagerApp', () => MediaManagerApp);
