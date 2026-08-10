package gateway

// geo-clusters 端点测试。覆盖：
//   - GET /api/media/geo-clusters 鉴权（无 token 401、store 未注入 503）。
//   - 有 EXIF GPS 的 JPEG 被聚类成簇：同点合并、远点分离。
//   - 无 GPS 的媒体被跳过（total_media_with_gps 不计）。
//
// 构造带 GPS 的最小 JPEG 由 makeJPEGWithGPS 完成：SOI + APP1(Exif) 段含 IFD0
// 指向 GPS IFD，GPS IFD 含 LatRef/Lat(d,m,s)/LngRef/Lng(d,m,s) RATIONAL×3。
// 非 JPEG / 无 EXIF / 无 GPS 三种情况由 service.ExtractGPSFromFile 返回 false。

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"media-manager/backend/internal/auth"
	"media-manager/backend/internal/config"
	"media-manager/backend/internal/service"
	"media-manager/backend/internal/storage"
)

// newGeoClustersGateway 构造带 storage + auth + userDirs 的 gateway，预注册一个
// 用户 alice，返回 (server, aliceToken, aliceUID, uploadsDir)。uploadsDir 在
// dataRoot/users/{uid}/uploads/ 下，测试直接写 JPEG 文件到此目录。
func newGeoClustersGateway(t *testing.T) (*Server, string, string, string) {
	t.Helper()
	dataRoot := t.TempDir()
	usersRoot := filepath.Join(dataRoot, "users")
	if err := os.MkdirAll(usersRoot, 0o755); err != nil {
		t.Fatalf("mkdir users root: %v", err)
	}
	userDirs := service.NewUserDirs(usersRoot)

	store, err := storage.Open(filepath.Join(dataRoot, "test.db"))
	if err != nil {
		t.Fatalf("storage.Open: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })

	idSeq := 0
	authSvc, err := auth.New(
		auth.NewStoreBridge(store), "geo-clusters-test-secret", 3600, config.SignupOpen,
		withCountingIDGen(&idSeq),
		auth.WithClock(func() time.Time { return time.Now().Add(time.Hour) }),
	)
	if err != nil {
		t.Fatalf("auth.New: %v", err)
	}
	res, err := authSvc.Register(context.Background(), auth.RegisterRequest{Username: "alice", Password: "pw123456"})
	if err != nil {
		t.Fatalf("register alice: %v", err)
	}

	svc := service.NewMediaService(userDirs, "")
	srv := NewServer(":0", OpenClawConfig{}, svc, userDirs, authSvc)
	srv.SetStore(store)

	uploadsDir, err := userDirs.UploadsDir(res.User.ID)
	if err != nil {
		t.Fatalf("uploads dir: %v", err)
	}
	return srv, res.Token, res.User.ID, uploadsDir
}

// doGeoClusters 经 authMiddleware 包裹的 mux 发请求，返回状态码与解析后的 body。
func doGeoClusters(t *testing.T, srv *Server, req *http.Request) (int, map[string]any) {
	t.Helper()
	rec := httptest.NewRecorder()
	srv.authMiddleware(srv.mux).ServeHTTP(rec, req)
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	return rec.Code, m
}

// makeJPEGWithGPS 构造一个含 EXIF GPS 的最小 JPEG 文件（SOI + APP1 Exif 段 + EOI）。
// lat/lng 为十进制度数，内部转为度分秒 RATIONAL×3 写入 GPS IFD。
// 结构：JPEG SOI(FFD8) + APP1(FFE1, len, "Exif\0\0", TIFF) + EOI(FFD9)。
// TIFF（little-endian）：header(II, 0x002A, ifd0Offset) + IFD0(1 entry: GPSInfoIFDPointer)
// + GPS IFD(4 entries: LatRef/LongRef ASCII + Lat/Long RATIONAL×3) + RATIONAL data。
func makeJPEGWithGPS(t *testing.T, path string, lat, lng float64) {
	t.Helper()
	// 决定 N/S、E/W 并取绝对值的度分秒。
	latRef := "N"
	if lat < 0 {
		latRef = "S"
	}
	lngRef := "E"
	if lng < 0 {
		lngRef = "W"
	}
	la := absFloat(lat)
	ln := absFloat(lng)
	latDeg := int(la)
	latMin := int((la - float64(latDeg)) * 60)
	latSec := (la - float64(latDeg) - float64(latMin)/60.0) * 3600.0
	lngDeg := int(ln)
	lngMin := int((ln - float64(lngDeg)) * 60)
	lngSec := (ln - float64(lngDeg) - float64(lngMin)/60.0) * 3600.0

	// 布局（TIFF 起点 = 0）：
	//   0..7  TIFF header (8 bytes)
	//   8     IFD0: count(2) + 1 entry(12) + nextIFD(4) = 18 bytes → 8..25
	//   26    GPS IFD: count(2) + 4 entries(48) + nextIFD(4) = 54 bytes → 26..79
	//   80    ASCII ref data: "N\0" + "E\0" = 4 bytes → 80..83 (LatRef at 80, LngRef at 82)
	//   84    RATIONAL data: 3*8 (Lat) + 3*8 (Lng) = 48 bytes → 84..131
	const (
		tiffStart      = 0
		ifd0Offset     = 8
		ifd0Len        = 2 + 12 + 4               // 18
		gpsIFDOffset   = ifd0Offset + ifd0Len     // 26
		gpsIFDLen      = 2 + 4*12 + 4             // 54
		refDataOffset  = gpsIFDOffset + gpsIFDLen // 80
		latRefOff      = refDataOffset            // 80
		lngRefOff      = refDataOffset + 2        // 82
		rationalOffset = refDataOffset + 4        // 84
		latRatOff      = rationalOffset           // 84
		lngRatOff      = rationalOffset + 24      // 108
		tiffTotal      = rationalOffset + 48      // 132
	)
	tiff := make([]byte, tiffTotal)
	le := binary.LittleEndian
	// TIFF header: "II" + 0x002A + ifd0Offset。
	tiff[0] = 'I'
	tiff[1] = 'I'
	le.PutUint16(tiff[2:4], 0x002A)
	le.PutUint32(tiff[4:8], uint32(ifd0Offset))

	// IFD0：1 entry (GPSInfoIFDPointer, tag 0x8825, type LONG=4, count 1, value=gpsIFDOffset)。
	le.PutUint16(tiff[ifd0Offset:ifd0Offset+2], 1) // entry count
	e0 := ifd0Offset + 2
	le.PutUint16(tiff[e0:e0+2], 0x8825)                  // tag
	le.PutUint16(tiff[e0+2:e0+4], 4)                     // type LONG
	le.PutUint32(tiff[e0+4:e0+8], 1)                     // count
	le.PutUint32(tiff[e0+8:e0+12], uint32(gpsIFDOffset)) // value
	le.PutUint32(tiff[e0+12:e0+16], 0)                   // next IFD = 0

	// GPS IFD：4 entries。每个 entry：tag(2)+type(2)+count(4)+value-or-offset(4)。
	le.PutUint16(tiff[gpsIFDOffset:gpsIFDOffset+2], 4) // entry count
	g := gpsIFDOffset + 2
	// Entry 1: GPSLatitudeRef (0x0001, ASCII=2, count 2, offset latRefOff)
	le.PutUint16(tiff[g:g+2], 0x0001)
	le.PutUint16(tiff[g+2:g+4], 2)
	le.PutUint32(tiff[g+4:g+8], 2)
	le.PutUint32(tiff[g+8:g+12], uint32(latRefOff))
	g += 12
	// Entry 2: GPSLatitude (0x0002, RATIONAL=5, count 3, offset latRatOff)
	le.PutUint16(tiff[g:g+2], 0x0002)
	le.PutUint16(tiff[g+2:g+4], 5)
	le.PutUint32(tiff[g+4:g+8], 3)
	le.PutUint32(tiff[g+8:g+12], uint32(latRatOff))
	g += 12
	// Entry 3: GPSLongitudeRef (0x0003, ASCII=2, count 2, offset lngRefOff)
	le.PutUint16(tiff[g:g+2], 0x0003)
	le.PutUint16(tiff[g+2:g+4], 2)
	le.PutUint32(tiff[g+4:g+8], 2)
	le.PutUint32(tiff[g+8:g+12], uint32(lngRefOff))
	g += 12
	// Entry 4: GPSLongitude (0x0004, RATIONAL=5, count 3, offset lngRatOff)
	le.PutUint16(tiff[g:g+2], 0x0004)
	le.PutUint16(tiff[g+2:g+4], 5)
	le.PutUint32(tiff[g+4:g+8], 3)
	le.PutUint32(tiff[g+8:g+12], uint32(lngRatOff))
	g += 12
	le.PutUint32(tiff[g:g+4], 0) // next IFD = 0

	// ASCII ref data。
	tiff[latRefOff] = latRef[0]
	tiff[latRefOff+1] = 0
	tiff[lngRefOff] = lngRef[0]
	tiff[lngRefOff+1] = 0

	// RATIONAL data：每个 8 字节 (num u32 + den u32)。度分秒分母固定为合适的精度。
	writeRat := func(off int, num, den uint32) {
		le.PutUint32(tiff[off:off+4], num)
		le.PutUint32(tiff[off+4:off+8], den)
	}
	// Lat: deg/1, min/1, sec*1000000/1000000
	writeRat(latRatOff, uint32(latDeg), 1)
	writeRat(latRatOff+8, uint32(latMin), 1)
	writeRat(latRatOff+16, uint32(latSec*1000000), 1000000)
	// Lng: deg/1, min/1, sec*1000000/1000000
	writeRat(lngRatOff, uint32(lngDeg), 1)
	writeRat(lngRatOff+8, uint32(lngMin), 1)
	writeRat(lngRatOff+16, uint32(lngSec*1000000), 1000000)

	// 组装 JPEG：SOI + APP1(Exif) + EOI。
	var buf bytes.Buffer
	buf.Write([]byte{0xFF, 0xD8}) // SOI
	// APP1 marker + length + "Exif\0\0" + tiff。
	app1Payload := append([]byte("Exif\x00\x00"), tiff...)
	app1Len := uint16(2 + len(app1Payload)) // length 含自身 2 字节
	buf.Write([]byte{0xFF, 0xE1})
	var lb [2]byte
	binary.BigEndian.PutUint16(lb[:], app1Len)
	buf.Write(lb[:])
	buf.Write(app1Payload)
	buf.Write([]byte{0xFF, 0xD9}) // EOI

	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatalf("write jpeg %s: %v", path, err)
	}
}

func absFloat(f float64) float64 {
	if f < 0 {
		return -f
	}
	return f
}

// createGPSMediaInStore 在 store 中插入一条 Media 记录（未软删），用于列表可见性。
func createGPSMediaInStore(t *testing.T, store *storage.Store, uid, mediaID string) {
	t.Helper()
	m := &storage.Media{
		ID:        mediaID,
		UserID:    uid,
		Filename:  mediaID + ".jpg",
		Type:      "IMAGE",
		Size:      1024,
		Mime:      "image/jpeg",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
	if err := store.CreateMedia(context.Background(), m); err != nil {
		t.Fatalf("create media %s: %v", mediaID, err)
	}
}

func TestGeoClustersUnauthorized(t *testing.T) {
	srv, _, _, _ := newGeoClustersGateway(t)
	// 无 token → 401。
	req := httptest.NewRequest(http.MethodGet, "/api/media/geo-clusters", nil)
	code, _ := doGeoClusters(t, srv, req)
	if code != http.StatusUnauthorized {
		t.Fatalf("no auth: want 401, got %d", code)
	}
}

func TestGeoClustersServiceUnavailable(t *testing.T) {
	// store 未注入 → 503。构造一个有 auth 但无 store 的 server。
	dataRoot := t.TempDir()
	ud := service.NewUserDirs(filepath.Join(dataRoot, "users"))
	authSvc, err := auth.New(auth.NewStoreBridge(mustOpenStore(t, filepath.Join(dataRoot, "a.db"))), "s", 3600, config.SignupOpen)
	if err != nil {
		t.Fatalf("auth.New: %v", err)
	}
	res, err := authSvc.Register(context.Background(), auth.RegisterRequest{Username: "u", Password: "pw123456"})
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	// NewServer 不调 SetStore，故 s.store==nil。
	srv := NewServer(":0", OpenClawConfig{}, nil, ud, authSvc)
	req := httptest.NewRequest(http.MethodGet, "/api/media/geo-clusters", nil)
	req.Header.Set("Authorization", "Bearer "+res.Token)
	code, body := doGeoClusters(t, srv, req)
	if code != http.StatusServiceUnavailable {
		t.Fatalf("no store: want 503, got %d body=%v", code, body)
	}
}

func mustOpenStore(t *testing.T, path string) *storage.Store {
	t.Helper()
	s, err := storage.Open(path)
	if err != nil {
		t.Fatalf("storage.Open: %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

func TestGeoClustersClustersGPSMedia(t *testing.T) {
	srv, token, uid, uploadsDir := newGeoClustersGateway(t)

	// 三张带 GPS 的 JPEG：两张在同一点（上海，~31.23,121.47），一张在远处（北京，39.90,116.40）。
	// 另一张无 GPS 的 JPEG（纯 SOI+EOI，无 APP1）。
	IDs := []struct {
		id  string
		lat float64
		lng float64
		gps bool
	}{
		{"m-shanghai-1", 31.2304, 121.4737, true},
		{"m-shanghai-2", 31.2305, 121.4738, true}, // ~11m，同簇
		{"m-beijing-1", 39.9042, 116.4074, true},  // 远点，独立簇
		{"m-nogps-1", 0, 0, false},                // 无 GPS，跳过
	}
	for _, m := range IDs {
		path := filepath.Join(uploadsDir, m.id+".jpg")
		if m.gps {
			makeJPEGWithGPS(t, path, m.lat, m.lng)
		} else {
			// 极简 JPEG 无 EXIF。
			if err := os.WriteFile(path, []byte{0xFF, 0xD8, 0xFF, 0xD9}, 0o644); err != nil {
				t.Fatalf("write nogps jpeg: %v", err)
			}
		}
		createGPSMediaInStore(t, srv.store, uid, m.id)
	}

	// 同时验证解析器正确：直接调 service.ExtractGPSFromFile。
	lat, lng, ok := service.ExtractGPSFromFile(filepath.Join(uploadsDir, "m-shanghai-1.jpg"))
	if !ok {
		t.Fatalf("ExtractGPSFromFile m-shanghai-1: want ok, got false")
	}
	if absFloat(lat-31.2304) > 0.001 || absFloat(lng-121.4737) > 0.001 {
		t.Fatalf("ExtractGPSFromFile m-shanghai-1: got lat=%v lng=%v, want ~31.2304/121.4737", lat, lng)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/media/geo-clusters", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	code, resp := doGeoClusters(t, srv, req)
	if code != http.StatusOK {
		t.Fatalf("geo-clusters: want 200, got %d body=%v", code, resp)
	}

	totalWithGPS, _ := resp["total_media_with_gps"].(float64)
	if int(totalWithGPS) != 3 {
		t.Fatalf("total_media_with_gps: want 3, got %v", totalWithGPS)
	}
	totalClusters, _ := resp["total_clusters"].(float64)
	if int(totalClusters) != 2 {
		t.Fatalf("total_clusters: want 2 (shanghai cluster + beijing cluster), got %v", totalClusters)
	}

	clusters, _ := resp["clusters"].([]any)
	if len(clusters) != 2 {
		t.Fatalf("clusters len: want 2, got %d", len(clusters))
	}
	// 找到 count=2 的簇（上海）与 count=1 的簇（北京），并校验坐标大致正确。
	var foundShanghai, foundBeijing bool
	for _, c := range clusters {
		cm := c.(map[string]any)
		cnt := int(cm["count"].(float64))
		lat := cm["lat"].(float64)
		lng := cm["lng"].(float64)
		thumb := cm["thumb_media_id"].(string)
		if thumb == "" {
			t.Fatalf("cluster thumb_media_id empty: %v", cm)
		}
		switch cnt {
		case 2:
			foundShanghai = true
			if absFloat(lat-31.2304) > 0.01 || absFloat(lng-121.4737) > 0.01 {
				t.Fatalf("shanghai cluster: got lat=%v lng=%v", lat, lng)
			}
		case 1:
			foundBeijing = true
			if absFloat(lat-39.9042) > 0.01 || absFloat(lng-116.4074) > 0.01 {
				t.Fatalf("beijing cluster: got lat=%v lng=%v", lat, lng)
			}
		}
	}
	if !foundShanghai || !foundBeijing {
		t.Fatalf("missing expected clusters: shanghai=%v beijing=%v", foundShanghai, foundBeijing)
	}
	// name 字段应为空串。
	for _, c := range clusters {
		cm := c.(map[string]any)
		if name, _ := cm["name"].(string); name != "" {
			t.Fatalf("cluster name: want empty, got %q", name)
		}
	}
}

func TestGeoClustersEmpty(t *testing.T) {
	srv, token, _, _ := newGeoClustersGateway(t)
	// 无任何媒体 → 200 空聚类。
	req := httptest.NewRequest(http.MethodGet, "/api/media/geo-clusters", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	code, resp := doGeoClusters(t, srv, req)
	if code != http.StatusOK {
		t.Fatalf("empty geo-clusters: want 200, got %d", code)
	}
	if tc, _ := resp["total_clusters"].(float64); int(tc) != 0 {
		t.Fatalf("empty: want 0 clusters, got %v", tc)
	}
	clusters, _ := resp["clusters"].([]any)
	if len(clusters) != 0 {
		t.Fatalf("empty: want nil/empty clusters, got %d", len(clusters))
	}
}
