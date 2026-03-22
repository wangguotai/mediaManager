/**
 * 媒体库测试页面
 */

import React, {useEffect, useState} from 'react';
import {
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  StyleSheet,
  Image,
  Alert,
} from 'react-native';
import Colors from '../utils/Colors';
import CommonStyles from '../utils/Styles';
import MediaManager from '../modules/MediaManager';

const MediaGalleryScreen = ({onBack}) => {
  const [mediaList, setMediaList] = useState([]);
  const [loading, setLoading] = useState(false);
  const [selectedMedia, setSelectedMedia] = useState(null);

  useEffect(() => {
    // 监听媒体选择事件
    const subscription = MediaManager.onMediaSelected((mediaInfo) => {
      console.log('Media selected:', mediaInfo);
      Alert.alert('媒体已选择', JSON.stringify(mediaInfo, null, 2));
      setSelectedMedia(mediaInfo);
    });

    return () => {
      subscription.remove();
    };
  }, []);

  const handleGetMediaList = async () => {
    setLoading(true);
    try {
      const list = await MediaManager.getMediaList();
      setMediaList(list || []);
      Alert.alert('成功', `获取到 ${list?.length || 0} 个媒体文件`);
    } catch (error) {
      console.error('Get media list error:', error);
      Alert.alert('错误', error.message || '获取媒体列表失败');
    } finally {
      setLoading(false);
    }
  };

  const handleSelectMedia = async () => {
    try {
      const result = await MediaManager.selectMedia({
        mediaType: 'all',
        maxCount: 5,
      });
      console.log('Select media result:', result);
      Alert.alert('选择媒体', JSON.stringify(result, null, 2));
    } catch (error) {
      console.error('Select media error:', error);
      Alert.alert('错误', error.message || '选择媒体失败');
    }
  };

  const handleUploadMedia = async (mediaId) => {
    try {
      const result = await MediaManager.uploadMedia(mediaId, {
        quality: 'high',
      });
      console.log('Upload media result:', result);
      Alert.alert('上传媒体', JSON.stringify(result, null, 2));
    } catch (error) {
      console.error('Upload media error:', error);
      Alert.alert('错误', error.message || '上传媒体失败');
    }
  };

  return (
    <View style={CommonStyles.container}>
      {/* 头部 */}
      <View style={CommonStyles.header}>
        <TouchableOpacity onPress={onBack}>
          <Text style={styles.backButton}>← 返回</Text>
        </TouchableOpacity>
        <Text style={CommonStyles.headerTitle}>媒体库测试</Text>
        <View style={{width: 50}} />
      </View>

      <ScrollView style={CommonStyles.content}>
        {/* 操作按钮 */}
        <View style={styles.buttonGroup}>
          <TouchableOpacity
            style={[CommonStyles.button, styles.actionButton]}
            onPress={handleGetMediaList}
            disabled={loading}
          >
            <Text style={CommonStyles.buttonText}>
              {loading ? '加载中...' : '获取媒体列表'}
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[CommonStyles.button, styles.actionButton, styles.secondaryButton]}
            onPress={handleSelectMedia}
          >
            <Text style={[CommonStyles.buttonText, styles.secondaryButtonText]}>
              选择媒体
            </Text>
          </TouchableOpacity>
        </View>

        {/* 选中的媒体 */}
        {selectedMedia && (
          <View style={CommonStyles.card}>
            <Text style={CommonStyles.subtitle}>选中的媒体</Text>
            <Text style={styles.jsonText}>
              {JSON.stringify(selectedMedia, null, 2)}
            </Text>
            <TouchableOpacity
              style={[CommonStyles.button, {marginTop: 12}]}
              onPress={() => handleUploadMedia(selectedMedia.id)}
            >
              <Text style={CommonStyles.buttonText}>上传此媒体</Text>
            </TouchableOpacity>
          </View>
        )}

        {/* 媒体列表 */}
        {mediaList.length > 0 && (
          <View style={CommonStyles.card}>
            <Text style={CommonStyles.subtitle}>媒体列表</Text>
            {mediaList.map((media, index) => (
              <View key={index} style={styles.mediaItem}>
                <View style={styles.mediaIcon}>
                  <Text style={styles.mediaIconText}>
                    {media.type === 'image' ? '🖼️' : '🎬'}
                  </Text>
                </View>
                <View style={styles.mediaInfo}>
                  <Text style={styles.mediaId}>{media.id}</Text>
                  <Text style={styles.mediaType}>{media.type}</Text>
                </View>
                <TouchableOpacity
                  style={styles.uploadBtn}
                  onPress={() => handleUploadMedia(media.id)}
                >
                  <Text style={styles.uploadBtnText}>上传</Text>
                </TouchableOpacity>
              </View>
            ))}
          </View>
        )}

        {/* 说明 */}
        <View style={CommonStyles.card}>
          <Text style={CommonStyles.subtitle}>功能说明</Text>
          <Text style={CommonStyles.body}>
            • 获取媒体列表：调用 Native 方法获取设备媒体库{'\n'}
            • 选择媒体：打开原生媒体选择器{'\n'}
            • 上传媒体：将选中的媒体上传到服务器{'\n'}
            • 事件监听：接收 Native 端发送的事件
          </Text>
        </View>
      </ScrollView>
    </View>
  );
};

const styles = StyleSheet.create({
  backButton: {
    fontSize: 16,
    color: Colors.primary,
  },
  buttonGroup: {
    flexDirection: 'row',
    gap: 12,
    marginBottom: 16,
  },
  actionButton: {
    flex: 1,
  },
  secondaryButton: {
    backgroundColor: Colors.surface,
    borderWidth: 1,
    borderColor: Colors.primary,
  },
  secondaryButtonText: {
    color: Colors.primary,
  },
  jsonText: {
    fontFamily: 'monospace',
    fontSize: 12,
    color: Colors.text,
    backgroundColor: Colors.background,
    padding: 8,
    borderRadius: 4,
  },
  mediaItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: Colors.divider,
  },
  mediaIcon: {
    width: 48,
    height: 48,
    borderRadius: 8,
    backgroundColor: Colors.background,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 12,
  },
  mediaIconText: {
    fontSize: 24,
  },
  mediaInfo: {
    flex: 1,
  },
  mediaId: {
    fontSize: 14,
    fontWeight: '500',
    color: Colors.text,
  },
  mediaType: {
    fontSize: 12,
    color: Colors.textSecondary,
    marginTop: 2,
  },
  uploadBtn: {
    backgroundColor: Colors.primary,
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 6,
  },
  uploadBtnText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '500',
  },
});

export default MediaGalleryScreen;
