/**
 * Media Manager RN 动态模块入口（V7 §3.1 扩展版）
 *
 * 注册一个名为 "MediaManagerApp" 的 RN 组件，供 Android ReactHost 加载。
 * 多页面活动应用：首页(活动列表) / 详情 / 媒体挑战 / 成就，纯 RN 组件实现
 * 简易导航（useState + 底部 Tab Bar，不依赖 react-navigation）。
 *
 * 测试 RN 全链路支持：导航、组件复用、状态管理、网络请求、列表/进度/徽章渲染。
 */
import React, {useState, useEffect, useCallback, useMemo} from 'react';
import {
  AppRegistry,
  View,
  Text,
  ScrollView,
  StyleSheet,
  ActivityIndicator,
  Platform,
  TouchableOpacity,
  TouchableNativeFeedback,
  RefreshControl,
  StatusBar,
  LayoutAnimation,
  UIManager,
  Image,
} from 'react-native';

// Android 上启用 LayoutAnimation（iOS 默认开启）
if (Platform.OS === 'android' && UIManager.setLayoutAnimationEnabledExperimental) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

// ─── 主题色（Material 3 紫）───
const COLOR = {
  primary: '#6750A4',
  primaryLight: '#EADDFF',
  primaryDark: '#4F378B',
  onPrimary: '#FFFFFF',
  surface: '#FFFFFF',
  surfaceVariant: '#F3EDF7',
  onSurface: '#1C1B1F',
  onSurfaceVariant: '#49454F',
  outline: '#79747E',
  error: '#B3261E',
  errorContainer: '#F9DEDC',
  success: '#2E7D32',
  badgeBg: '#FFD8E4',
  badgeFg: '#7D5260',
};

const PAGES = ['home', 'detail', 'challenge', 'achievement'];
const PAGE_META = {
  home: {label: '活动', icon: '🏠'},
  detail: {label: '详情', icon: '📋'},
  challenge: {label: '挑战', icon: '🎯'},
  achievement: {label: '成就', icon: '🏆'},
};

// ─── 工具：跨平台可点击组件 ───
// Android 用 TouchableNativeFeedback（涟漪反馈），iOS 用 TouchableOpacity。
const Touchable = (props) => {
  if (Platform.OS === 'android' && TouchableNativeFeedback) {
    return (
      <TouchableNativeFeedback
        background={TouchableNativeFeedback.SelectableBackground()}
        onPress={props.onPress}>
        <View style={props.style}>{props.children}</View>
      </TouchableNativeFeedback>
    );
  }
  return (
    <TouchableOpacity style={props.style} onPress={props.onPress} activeOpacity={0.7}>
      {props.children}
    </TouchableOpacity>
  );
};

// ─── 统一组件：Header ───
// 带可选返回按钮的统一头部。title 必填，onBack 提供时渲染返回箭头。
function Header({title, subtitle, onBack}) {
  return (
    <View style={styles.header}>
      {onBack ? (
        <Touchable style={styles.backBtn} onPress={onBack}>
          <Text style={styles.backIcon}>‹</Text>
        </Touchable>
      ) : null}
      <View style={styles.headerCenter}>
        <Text style={styles.headerTitle}>{title}</Text>
        {subtitle ? <Text style={styles.headerSubtitle}>{subtitle}</Text> : null}
      </View>
      {/* 右侧占位，使标题居中（与返回按钮等宽） */}
      {onBack ? <View style={styles.backBtn} /> : null}
    </View>
  );
}

// ─── 统一组件：Card ───
// 可复用卡片容器，带点击事件。
function Card({children, onPress, style}) {
  const cardStyle = [styles.card, style];
  if (onPress) {
    return (
      <Touchable style={cardStyle} onPress={onPress}>
        {children}
      </Touchable>
    );
  }
  return <View style={cardStyle}>{children}</View>;
}

// ─── 统一组件：LoadingState ───
function LoadingState({text}) {
  return (
    <View style={styles.stateContainer}>
      <ActivityIndicator size="large" color={COLOR.primary} />
      <Text style={styles.stateText}>{text || '加载中...'}</Text>
    </View>
  );
}

// ─── 统一组件：ErrorState ───
function ErrorState({message, onRetry}) {
  return (
    <View style={styles.stateContainer}>
      <Text style={styles.stateIcon}>⚠️</Text>
      <Text style={styles.stateText}>{message || '加载失败'}</Text>
      {onRetry ? (
        <Touchable style={styles.retryBtn} onPress={onRetry}>
          <Text style={styles.retryBtnText}>重试</Text>
        </Touchable>
      ) : null}
    </View>
  );
}

// ─── 统一组件：EmptyState ───
function EmptyState({icon, message}) {
  return (
    <View style={styles.stateContainer}>
      <Text style={styles.stateIconBig}>{icon || '📭'}</Text>
      <Text style={styles.stateText}>{message || '暂无数据'}</Text>
    </View>
  );
}

// ─── 统一组件：ProgressBar ───
// 纯 View 实现的进度条，不依赖第三方库。
function ProgressBar({percent, height, color}) {
  const pct = Math.max(0, Math.min(100, percent || 0));
  return (
    <View style={[styles.progressTrack, {height: height || 8}]}>
      <View
        style={[
          styles.progressFill,
          {
            width: pct + '%',
            backgroundColor: color || COLOR.primary,
          },
        ]}
      />
    </View>
  );
}

// ─── 统一组件：Badge ───
function Badge({text, color, bg}) {
  return (
    <View style={[styles.badge, bg ? {backgroundColor: bg} : null]}>
      <Text style={[styles.badgeText, color ? {color} : null]}>{text}</Text>
    </View>
  );
}

// ─── 工具：倒计时计算 ───
// 将 ISO 日期串转为"剩余 X 天"格式。无效或已过期返回 null。
function countdown(expiresAt) {
  if (!expiresAt) return null;
  // 兼容 "2026-08-31" 与 "2026-08-31T23:59:59Z" 两种格式
  const dt = new Date(expiresAt);
  if (isNaN(dt.getTime())) return null;
  const diff = dt.getTime() - Date.now();
  if (diff <= 0) return '已结束';
  const days = Math.floor(diff / 86400000);
  const hours = Math.floor((diff % 86400000) / 3600000);
  if (days > 0) return `剩余 ${days} 天 ${hours} 小时`;
  const mins = Math.floor((diff % 3600000) / 60000);
  if (hours > 0) return `剩余 ${hours} 小时 ${mins} 分`;
  const secs = Math.floor((diff % 60000) / 1000);
  return `剩余 ${mins} 分 ${secs} 秒`;
}

// ─── 工具：网络请求封装 ───
// 统一注入 token（global.nativeAuthToken），处理 401/网络错误。
// 返回 {ok, status, data, error}。
async function apiGet(baseUrl, path, token) {
  try {
    const res = await fetch(baseUrl + path, {
      headers: token ? {Authorization: 'Bearer ' + token} : {},
    });
    if (!res.ok) {
      let errMsg = 'HTTP ' + res.status;
      try {
        const errBody = await res.json();
        if (errBody && errBody.error) errMsg = errBody.error;
      } catch (_) {}
      return {ok: false, status: res.status, data: null, error: errMsg};
    }
    const data = await res.json();
    return {ok: true, status: res.status, data, error: null};
  } catch (e) {
    return {ok: false, status: 0, data: null, error: '网络错误: ' + (e.message || String(e))};
  }
}

// ═══════════════════════════════════════════
// 页面组件：首页 HomeScreen（活动列表）
// ═══════════════════════════════════════════
function HomeScreen({baseUrl, token, onOpenDetail}) {
  const [promotions, setPromotions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(null);

  const loadPromotions = useCallback(async () => {
    const r = await apiGet(baseUrl, '/api/promotions', token);
    if (r.ok) {
      setPromotions(Array.isArray(r.data) ? r.data : []);
      setError(null);
    } else {
      setError(r.error);
      setPromotions([]);
    }
    setLoading(false);
    setRefreshing(false);
  }, [baseUrl, token]);

  useEffect(() => {
    loadPromotions();
  }, [loadPromotions]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadPromotions();
  }, [loadPromotions]);

  if (loading) {
    return (
      <View style={styles.page}>
        <Header title="活动中心" subtitle="React Native 动态模块" />
        <LoadingState text="加载活动列表..." />
      </View>
    );
  }

  return (
    <View style={styles.page}>
      <Header title="活动中心" subtitle="React Native 动态模块" />
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={[COLOR.primary]} />
        }>
        {error ? (
          <ErrorState message={error} onRetry={loadPromotions} />
        ) : promotions.length === 0 ? (
          <EmptyState icon="🎉" message="目前没有运营活动，请稍后再来" />
        ) : (
          promotions.map((promo, index) => {
            const cd = countdown(promo.expiresAt);
            return (
              <Card
                key={promo.id || index}
                style={styles.promoCard}
                onPress={() => onOpenDetail(promo)}>
                <View style={styles.promoCardHeader}>
                  <Badge text="活动" bg={COLOR.badgeBg} color={COLOR.badgeFg} />
                  {cd ? (
                    <Text style={[styles.cardMeta, {color: cd === '已结束' ? COLOR.outline : COLOR.primary}]}>
                      ⏱ {cd}
                    </Text>
                  ) : null}
                </View>
                <Text style={styles.cardTitle}>{promo.title || '未知活动'}</Text>
                {promo.imageUrl ? (
                  <Text style={styles.cardMeta}>🖼 {promo.imageUrl}</Text>
                ) : null}
                {promo.link ? (
                  <Text style={styles.cardMeta} numberOfLines={1}>🔗 {promo.link}</Text>
                ) : null}
                <Text style={styles.cardAction}>查看详情 ›</Text>
              </Card>
            );
          })
        )}
      </ScrollView>
    </View>
  );
}

// ═══════════════════════════════════════════
// 页面组件：详情页 DetailScreen
// ═══════════════════════════════════════════
function DetailScreen({promo, onBack, baseUrl, token}) {
  const [detail, setDetail] = useState(promo || null);
  const [loading, setLoading] = useState(!promo);
  const [error, setError] = useState(null);
  const [joined, setJoined] = useState(false);

  // 若仅传入 id（无完整对象）则拉取详情；有完整对象直接用。
  useEffect(() => {
    if (promo) {
      setDetail(promo);
      setLoading(false);
      return;
    }
    let cancelled = false;
    (async () => {
      setLoading(true);
      // 无 id 时无法拉取（实际从首页点击传入应有完整对象）
      setError(null);
      setLoading(false);
    })();
    return () => { cancelled = true; };
  }, [promo]);

  if (loading) {
    return (
      <View style={styles.page}>
        <Header title="活动详情" onBack={onBack} />
        <LoadingState />
      </View>
    );
  }
  if (!detail) {
    return (
      <View style={styles.page}>
        <Header title="活动详情" onBack={onBack} />
        <EmptyState icon="🔍" message="未找到活动信息" />
      </View>
    );
  }

  return (
    <View style={styles.page}>
      <Header title="活动详情" onBack={onBack} />
      <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent}>
        {/* 大图占位区 */}
        <View style={styles.detailHero}>
          {detail.imageUrl ? (
            <Image source={{uri: detail.imageUrl}} style={styles.detailImage} resizeMode="cover" />
          ) : (
            <View style={[styles.detailImage, styles.detailImagePlaceholder]}>
              <Text style={styles.detailHeroIcon}>🎁</Text>
              <Text style={styles.detailHeroText}>活动主图</Text>
            </View>
          )}
        </View>

        <Card style={styles.detailCard}>
          <Badge text="活动" bg={COLOR.badgeBg} color={COLOR.badgeFg} />
          <Text style={styles.detailTitle}>{detail.title || '未知活动'}</Text>
          {detail.expiresAt ? (
            <Text style={styles.cardMeta}>⏱ 到期：{detail.expiresAt}</Text>
          ) : null}
          {detail.participantsCount != null ? (
            <Text style={styles.cardMeta}>👥 已有 {detail.participantsCount} 人参与</Text>
          ) : null}
        </Card>

        {/* 规则 */}
        <Card>
          <Text style={styles.cardTitle}>活动规则</Text>
          <Text style={styles.cardText}>
            {detail.rules ||
              '1. 上传夏日主题照片；\n2. 每张照片需含位置信息；\n3. 不可重复上传同一张照片。'}
          </Text>
        </Card>

        {/* 描述 */}
        <Card>
          <Text style={styles.cardTitle}>活动描述</Text>
          <Text style={styles.cardText}>
            {detail.description || detail.title || '参与本次运营活动，赢取丰富奖励。点击下方按钮立即参与。'}
          </Text>
        </Card>

        {/* 参与按钮（纯 UI） */}
        <Touchable
          style={[styles.joinBtn, joined ? styles.joinBtnDone : null]}
          onPress={() => setJoined(true)}>
          <Text style={styles.joinBtnText}>{joined ? '✓ 已参与' : '立即参与'}</Text>
        </Touchable>

        <View style={styles.bottomSpacer} />
      </ScrollView>
    </View>
  );
}

// ═══════════════════════════════════════════
// 页面组件：挑战页 ChallengeScreen
// ═══════════════════════════════════════════
function ChallengeScreen({baseUrl, token}) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(null);

  const loadChallenge = useCallback(async () => {
    const r = await apiGet(baseUrl, '/api/promotions/challenge', token);
    if (r.ok) {
      setData(r.data);
      setError(null);
    } else {
      setError(r.error);
      setData(null);
    }
    setLoading(false);
    setRefreshing(false);
  }, [baseUrl, token]);

  useEffect(() => {
    loadChallenge();
  }, [loadChallenge]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadChallenge();
  }, [loadChallenge]);

  if (loading) {
    return (
      <View style={styles.page}>
        <Header title="媒体挑战" subtitle="本月挑战进行中" />
        <LoadingState text="加载挑战数据..." />
      </View>
    );
  }

  const challenge = data?.challenge;
  const rankings = Array.isArray(data?.rankings) ? data.rankings : [];

  return (
    <View style={styles.page}>
      <Header title="媒体挑战" subtitle="本月挑战进行中" />
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={[COLOR.primary]} />
        }>
        {error ? (
          <ErrorState message={error} onRetry={loadChallenge} />
        ) : !challenge ? (
          <EmptyState icon="🎯" message="暂无挑战活动" />
        ) : (
          <>
            {/* 挑战概览卡片 */}
            <Card style={styles.challengeCard}>
              <View style={styles.challengeHeader}>
                <Text style={styles.challengeIconBig}>🎯</Text>
                <View style={styles.challengeHeaderText}>
                  <Text style={styles.cardTitle}>{challenge.title}</Text>
                  <Text style={styles.cardText}>{challenge.description}</Text>
                </View>
              </View>

              {/* 进度 */}
              <View style={styles.progressSection}>
                <View style={styles.progressLabelRow}>
                  <Text style={styles.progressLabel}>
                    进度：{challenge.current_count || 0} / {challenge.target_count || 10}
                  </Text>
                  <Text style={styles.progressPct}>{challenge.progress_pct || 0}%</Text>
                </View>
                <ProgressBar percent={challenge.progress_pct || 0} height={12} />
              </View>

              {/* 参与人数 + 奖励 */}
              <View style={styles.challengeMetaRow}>
                <View style={styles.challengeMetaItem}>
                  <Text style={styles.challengeMetaValue}>{challenge.participants || 0}</Text>
                  <Text style={styles.challengeMetaLabel}>参与人数</Text>
                </View>
                <View style={styles.challengeMetaItem}>
                  <Text style={styles.challengeMetaValue}>🎁</Text>
                  <Text style={styles.challengeMetaLabel}>{challenge.reward || '神秘奖励'}</Text>
                </View>
                <View style={styles.challengeMetaItem}>
                  <Text style={styles.challengeMetaValue}>{challenge.end_date || '-'}</Text>
                  <Text style={styles.challengeMetaLabel}>截止日期</Text>
                </View>
              </View>
            </Card>

            {/* 排行榜 */}
            <Card>
              <Text style={styles.cardTitle}>🏆 排行榜</Text>
              {rankings.length === 0 ? (
                <Text style={styles.cardText}>暂无排行数据</Text>
              ) : (
                rankings.map((r, i) => {
                  const rank = r.rank || i + 1;
                  const medal = rank === 1 ? '🥇' : rank === 2 ? '🥈' : rank === 3 ? '🥉' : '  ';
                  return (
                    <View key={i} style={styles.rankRow}>
                      <Text style={styles.rankMedal}>{medal}</Text>
                      <Text style={styles.rankNum}>#{rank}</Text>
                      <Text style={styles.rankName}>{r.username || '匿名'}</Text>
                      <Text style={styles.rankCount}>{r.count || 0} 张</Text>
                    </View>
                  );
                })
              )}
            </Card>
          </>
        )}
        <View style={styles.bottomSpacer} />
      </ScrollView>
    </View>
  );
}

// ═══════════════════════════════════════════
// 页面组件：成就页 AchievementScreen
// ═══════════════════════════════════════════

// 徽章定义：根据 stats.count 等指标判断点亮状态与进度。
// each: { id, emoji, name, threshold, desc }
const ACHIEVEMENT_DEFS = [
  {id: 'uploader', emoji: '📸', name: '上传达人', threshold: 5, desc: '上传 5 个媒体'},
  {id: 'organizer', emoji: '🗂️', name: '整理能手', threshold: 20, desc: '上传 20 个媒体'},
  {id: 'sharer', emoji: '🔗', name: '分享之星', threshold: 50, desc: '上传 50 个媒体'},
  {id: 'collector', emoji: '⭐', name: '收藏家', threshold: 100, desc: '上传 100 个媒体'},
  {id: 'videoMaster', emoji: '🎬', name: '视频大师', threshold: 10, desc: '拥有 10 个视频*'},
  {id: 'memoryGuard', emoji: '🛡️', name: '回忆守护者', threshold: 200, desc: '上传 200 个媒体'},
  {id: 'explorer', emoji: '🧭', name: '探索先锋', threshold: 1, desc: '上传首个媒体'},
  {id: 'archivist', emoji: '📚', name: '档案专家', threshold: 500, desc: '上传 500 个媒体'},
];

function AchievementScreen({baseUrl, token}) {
  const [health, setHealth] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(null);

  const loadHealth = useCallback(async () => {
    // /healthz 无需 token，但 apiGet 统一封装更省心
    const r = await apiGet(baseUrl, '/healthz', null);
    if (r.ok) {
      setHealth(r.data);
      setError(null);
    } else {
      setError(r.error);
      setHealth(null);
    }
    setLoading(false);
    setRefreshing(false);
  }, [baseUrl]);

  useEffect(() => {
    loadHealth();
  }, [loadHealth]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadHealth();
  }, [loadHealth]);

  // 根据媒体总数判断各徽章点亮状态与进度
  const mediaCount = health?.media_count || 0;
  const badges = useMemo(() => {
    return ACHIEVEMENT_DEFS.map((def) => {
      // 视频大师在缺乏视频数时用媒体总数近似（mock 逻辑）
      const current = def.id === 'videoMaster' ? Math.floor(mediaCount / 3) : mediaCount;
      const lit = current >= def.threshold;
      const pct = def.threshold > 0 ? Math.min(100, Math.round((current / def.threshold) * 100)) : 0;
      return {...def, current, lit, pct};
    });
  }, [mediaCount]);

  if (loading) {
    return (
      <View style={styles.page}>
        <Header title="成就" subtitle="徽章收集墙" />
        <LoadingState text="加载成就数据..." />
      </View>
    );
  }

  const litCount = badges.filter((b) => b.lit).length;

  return (
    <View style={styles.page}>
      <Header title="成就" subtitle="徽章收集墙" />
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={[COLOR.primary]} />
        }>
        {error ? (
          <ErrorState message={error} onRetry={loadHealth} />
        ) : (
          <>
            {/* 总览卡片 */}
            <Card style={styles.achOverviewCard}>
              <Text style={styles.cardTitle}>🏆 我的成就</Text>
              <View style={styles.statRow}>
                <View style={styles.statItem}>
                  <Text style={styles.statValue}>{litCount}</Text>
                  <Text style={styles.statLabel}>已点亮</Text>
                </View>
                <View style={styles.statItem}>
                  <Text style={styles.statValue}>{badges.length}</Text>
                  <Text style={styles.statLabel}>总徽章</Text>
                </View>
                <View style={styles.statItem}>
                  <Text style={styles.statValue}>{mediaCount}</Text>
                  <Text style={styles.statLabel}>媒体总数</Text>
                </View>
              </View>
            </Card>

            {/* 徽章网格（2 列） */}
            <View style={styles.badgeGrid}>
              {badges.map((b, i) => (
                <View
                  key={b.id}
                  style={[styles.badgeCell, b.lit ? styles.badgeCellLit : styles.badgeCellDim]}>
                  <Text style={[styles.badgeEmoji, !b.lit && styles.badgeEmojiDim]}>{b.emoji}</Text>
                  <Text style={[styles.badgeName, !b.lit && styles.badgeNameDim]}>{b.name}</Text>
                  <Text style={styles.badgeDesc}>{b.desc}</Text>
                  {b.lit ? (
                    <Text style={styles.badgeLitTag}>✓ 已点亮</Text>
                  ) : (
                    <>
                      <ProgressBar percent={b.pct} height={5} color={COLOR.outline} />
                      <Text style={styles.badgeProgressText}>
                        {b.current}/{b.threshold}
                      </Text>
                    </>
                  )}
                </View>
              ))}
            </View>

            <Text style={styles.achFootnote}>* 视频大师徽章基于媒体总数估算，仅供参考</Text>
          </>
        )}
        <View style={styles.bottomSpacer} />
      </ScrollView>
    </View>
  );
}

// ═══════════════════════════════════════════
// 底部 Tab Bar
// ═══════════════════════════════════════════
function TabBar({current, onChange}) {
  return (
    <View style={styles.tabBar}>
      {PAGES.map((page) => {
        const meta = PAGE_META[page];
        const active = current === page;
        return (
          <Touchable
            key={page}
            style={styles.tabItem}
            onPress={() => onChange(page)}>
            <View style={styles.tabItemInner}>
              <Text style={[styles.tabIcon, active && styles.tabIconActive]}>{meta.icon}</Text>
              <Text style={[styles.tabLabel, active && styles.tabLabelActive]}>{meta.label}</Text>
              {active ? <View style={styles.tabIndicator} /> : null}
            </View>
          </Touchable>
        );
      })}
    </View>
  );
}

// ═══════════════════════════════════════════
// 主组件：MediaManagerApp（导航容器）
// ═══════════════════════════════════════════
function MediaManagerApp(props) {
  // 当前页面：'home' | 'detail' | 'challenge' | 'achievement'
  const [page, setPage] = useState('home');
  // 详情页入参：从首页点击传入的 promo 对象
  const [selectedPromo, setSelectedPromo] = useState(null);

  // V7 §3.3：从 initialProps 获取后端地址 + token（Android 侧 Bundle 注入）
  const baseUrl = props?.backendUrl || global.nativeBackendUrl || 'http://192.168.31.251:8080';
  const token = props?.authToken || global.nativeAuthToken || '';

  // 页面切换动画（LayoutAnimation，可选）
  const switchPage = useCallback((next) => {
    if (next === page) return;
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    setPage(next);
  }, [page]);

  // 打开详情：暂存 promo 对象后切到 detail 页
  const openDetail = useCallback((promo) => {
    setSelectedPromo(promo);
    switchPage('detail');
  }, [switchPage]);

  // 渲染当前页面
  let content;
  if (page === 'home') {
    content = <HomeScreen baseUrl={baseUrl} token={token} onOpenDetail={openDetail} />;
  } else if (page === 'detail') {
    content = (
      <DetailScreen
        promo={selectedPromo}
        onBack={() => switchPage('home')}
        baseUrl={baseUrl}
        token={token}
      />
    );
  } else if (page === 'challenge') {
    content = <ChallengeScreen baseUrl={baseUrl} token={token} />;
  } else if (page === 'achievement') {
    content = <AchievementScreen baseUrl={baseUrl} token={token} />;
  }

  return (
    <View style={styles.appRoot}>
      <StatusBar backgroundColor={COLOR.primaryDark} barStyle="light-content" />
      <View style={styles.contentArea}>{content}</View>
      <TabBar current={page} onChange={switchPage} />
    </View>
  );
}

AppRegistry.registerComponent('MediaManagerApp', () => MediaManagerApp);

// ─── 样式 ───
const styles = StyleSheet.create({
  appRoot: {
    flex: 1,
    backgroundColor: COLOR.surfaceVariant,
  },
  contentArea: {
    flex: 1,
  },
  page: {
    flex: 1,
    backgroundColor: COLOR.surfaceVariant,
  },

  // ── Header ──
  header: {
    backgroundColor: COLOR.primary,
    paddingVertical: 14,
    paddingHorizontal: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  headerCenter: {
    flex: 1,
    alignItems: 'center',
  },
  headerTitle: {
    color: COLOR.onPrimary,
    fontSize: 20,
    fontWeight: 'bold',
  },
  headerSubtitle: {
    color: COLOR.onPrimary,
    fontSize: 13,
    opacity: 0.8,
    marginTop: 2,
  },
  backBtn: {
    width: 44,
    height: 44,
    justifyContent: 'center',
    alignItems: 'center',
  },
  backIcon: {
    color: COLOR.onPrimary,
    fontSize: 36,
    fontWeight: '300',
    lineHeight: 40,
    marginTop: -4,
  },

  // ── Scroll ──
  scroll: {
    flex: 1,
  },
  scrollContent: {
    paddingBottom: 16,
  },
  bottomSpacer: {
    height: 24,
  },

  // ── Card ──
  card: {
    backgroundColor: COLOR.surface,
    marginHorizontal: 16,
    marginVertical: 8,
    borderRadius: 12,
    padding: 16,
    // Android elevation
    elevation: 2,
    // iOS shadow
    shadowColor: '#000',
    shadowOffset: {width: 0, height: 2},
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    color: COLOR.onSurface,
    marginBottom: 8,
  },
  cardText: {
    fontSize: 14,
    color: COLOR.onSurfaceVariant,
    lineHeight: 20,
  },
  cardMeta: {
    fontSize: 12,
    color: COLOR.outline,
    marginTop: 4,
  },
  cardAction: {
    fontSize: 13,
    color: COLOR.primary,
    fontWeight: '600',
    marginTop: 10,
  },

  // ── Badge ──
  badge: {
    backgroundColor: COLOR.badgeBg,
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 4,
    alignSelf: 'flex-start',
  },
  badgeText: {
    color: COLOR.badgeFg,
    fontSize: 12,
    fontWeight: 'bold',
  },
  promoCardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },

  // ── 状态组件 ──
  stateContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 60,
    paddingHorizontal: 32,
  },
  stateText: {
    marginTop: 12,
    color: COLOR.outline,
    fontSize: 14,
    textAlign: 'center',
  },
  stateIcon: {
    fontSize: 40,
  },
  stateIconBig: {
    fontSize: 56,
  },
  retryBtn: {
    marginTop: 16,
    backgroundColor: COLOR.primary,
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 8,
  },
  retryBtnText: {
    color: COLOR.onPrimary,
    fontSize: 14,
    fontWeight: '600',
  },

  // ── ProgressBar ──
  progressTrack: {
    backgroundColor: COLOR.surfaceVariant,
    borderRadius: 4,
    overflow: 'hidden',
    width: '100%',
  },
  progressFill: {
    height: '100%',
    borderRadius: 4,
  },
  progressSection: {
    marginTop: 12,
  },
  progressLabelRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  progressLabel: {
    fontSize: 13,
    color: COLOR.onSurfaceVariant,
    fontWeight: '600',
  },
  progressPct: {
    fontSize: 13,
    color: COLOR.primary,
    fontWeight: 'bold',
  },

  // ── Detail 页 ──
  detailHero: {
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    overflow: 'hidden',
  },
  detailImage: {
    width: '100%',
    height: 180,
  },
  detailImagePlaceholder: {
    backgroundColor: COLOR.primaryLight,
    justifyContent: 'center',
    alignItems: 'center',
  },
  detailHeroIcon: {
    fontSize: 48,
  },
  detailHeroText: {
    color: COLOR.primaryDark,
    marginTop: 8,
    fontSize: 14,
  },
  detailCard: {
    marginTop: 12,
  },
  detailTitle: {
    fontSize: 20,
    fontWeight: 'bold',
    color: COLOR.onSurface,
    marginTop: 8,
  },
  joinBtn: {
    backgroundColor: COLOR.primary,
    marginHorizontal: 16,
    marginTop: 8,
    paddingVertical: 14,
    borderRadius: 10,
    alignItems: 'center',
    elevation: 2,
  },
  joinBtnDone: {
    backgroundColor: COLOR.success,
  },
  joinBtnText: {
    color: COLOR.onPrimary,
    fontSize: 16,
    fontWeight: 'bold',
  },

  // ── Challenge 页 ──
  challengeCard: {
    marginTop: 12,
  },
  challengeHeader: {
    flexDirection: 'row',
    alignItems: 'flex-start',
  },
  challengeIconBig: {
    fontSize: 36,
    marginRight: 12,
  },
  challengeHeaderText: {
    flex: 1,
  },
  challengeMetaRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    marginTop: 14,
    paddingTop: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: COLOR.outline,
  },
  challengeMetaItem: {
    alignItems: 'center',
  },
  challengeMetaValue: {
    fontSize: 15,
    fontWeight: 'bold',
    color: COLOR.primary,
  },
  challengeMetaLabel: {
    fontSize: 11,
    color: COLOR.outline,
    marginTop: 2,
  },
  rankRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#E7E0EC',
  },
  rankMedal: {
    fontSize: 18,
    width: 28,
  },
  rankNum: {
    fontSize: 13,
    color: COLOR.outline,
    width: 36,
    fontWeight: '600',
  },
  rankName: {
    flex: 1,
    fontSize: 14,
    color: COLOR.onSurface,
  },
  rankCount: {
    fontSize: 13,
    color: COLOR.primary,
    fontWeight: 'bold',
  },

  // ── Achievement 页 ──
  achOverviewCard: {
    marginTop: 12,
  },
  statRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingVertical: 8,
  },
  statItem: {
    alignItems: 'center',
  },
  statValue: {
    fontSize: 24,
    fontWeight: 'bold',
    color: COLOR.primary,
  },
  statLabel: {
    fontSize: 12,
    color: COLOR.outline,
    marginTop: 4,
  },
  badgeGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    paddingHorizontal: 8,
    marginTop: 4,
  },
  badgeCell: {
    width: '50%',
    padding: 8,
  },
  badgeCellInner: {
    flex: 1,
  },
  badgeCellLit: {
    // 容器样式由内层卡体现
  },
  badgeCellDim: {},
  badgeEmoji: {
    fontSize: 40,
    textAlign: 'center',
    backgroundColor: COLOR.surface,
    borderRadius: 10,
    paddingVertical: 12,
  },
  badgeEmojiDim: {
    opacity: 0.3,
    // 灰度：RN Text 无 filter，用 opacity + 背景色近似
  },
  badgeName: {
    fontSize: 14,
    fontWeight: 'bold',
    color: COLOR.onSurface,
    textAlign: 'center',
    marginTop: 6,
  },
  badgeNameDim: {
    color: COLOR.outline,
  },
  badgeDesc: {
    fontSize: 11,
    color: COLOR.outline,
    textAlign: 'center',
    marginTop: 2,
    minHeight: 28,
  },
  badgeLitTag: {
    fontSize: 11,
    color: COLOR.success,
    fontWeight: 'bold',
    textAlign: 'center',
    marginTop: 4,
  },
  badgeProgressText: {
    fontSize: 10,
    color: COLOR.outline,
    textAlign: 'center',
    marginTop: 3,
  },
  achFootnote: {
    fontSize: 11,
    color: COLOR.outline,
    textAlign: 'center',
    marginHorizontal: 24,
    marginTop: 12,
    fontStyle: 'italic',
  },

  // ── Tab Bar ──
  tabBar: {
    flexDirection: 'row',
    backgroundColor: COLOR.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#CAC4D0',
    paddingBottom: Platform.OS === 'ios' ? 0 : 0,
    // 避免底部内容被遮挡
    elevation: 8,
    shadowColor: '#000',
    shadowOffset: {width: 0, height: -2},
    shadowOpacity: 0.08,
    shadowRadius: 4,
  },
  tabItem: {
    flex: 1,
    paddingVertical: 8,
  },
  tabItemInner: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  tabIcon: {
    fontSize: 22,
  },
  tabIconActive: {
    // emoji 无法直接改色，用缩放强调
    transform: [{scale: 1.15}],
  },
  tabLabel: {
    fontSize: 11,
    color: COLOR.outline,
    marginTop: 2,
  },
  tabLabelActive: {
    color: COLOR.primary,
    fontWeight: 'bold',
  },
  tabIndicator: {
    width: 24,
    height: 3,
    backgroundColor: COLOR.primary,
    borderRadius: 2,
    marginTop: 4,
  },
});