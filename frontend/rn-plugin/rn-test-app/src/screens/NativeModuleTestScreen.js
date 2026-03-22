/**
 * 原生模块测试页面
 */

import React, {useState, useEffect} from 'react';
import {
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  StyleSheet,
  Alert,
  TextInput,
} from 'react-native';
import Colors from '../utils/Colors';
import CommonStyles from '../utils/Styles';
import MediaManager from '../modules/MediaManager';

const NativeModuleTestScreen = ({onBack}) => {
  const [logs, setLogs] = useState([]);
  const [testData, setTestData] = useState('{"key": "value"}');
  const [eventCount, setEventCount] = useState(0);

  useEffect(() => {
    // 监听所有事件
    const mediaSub = MediaManager.onMediaSelected((data) => {
      addLog('Event: onMediaSelected', data);
    });

    const uploadSub = MediaManager.onMediaUploaded((data) => {
      addLog('Event: onMediaUploaded', data);
    });

    return () => {
      mediaSub.remove();
      uploadSub.remove();
    };
  }, []);

  const addLog = (action, result, error = null) => {
    const timestamp = new Date().toLocaleTimeString();
    setLogs(prev => [{
      id: Date.now(),
      timestamp,
      action,
      result: error ? null : result,
      error,
    }, ...prev].slice(0, 50)); // 只保留最近 50 条
  };

  const clearLogs = () => {
    setLogs([]);
  };

  // 测试获取媒体列表
  const testGetMediaList = async () => {
    try {
      addLog('Call: getMediaList()', null);
      const result = await MediaManager.getMediaList();
      addLog('Result: getMediaList()', result);
    } catch (error) {
      addLog('Error: getMediaList()', null, error.message);
    }
  };

  // 测试选择媒体
  const testSelectMedia = async () => {
    try {
      const options = { mediaType: 'image', maxCount: 3 };
      addLog('Call: selectMedia()', options);
      const result = await MediaManager.selectMedia(options);
      addLog('Result: selectMedia()', result);
    } catch (error) {
      addLog('Error: selectMedia()', null, error.message);
    }
  };

  // 测试上传媒体
  const testUploadMedia = async () => {
    try {
      const mediaId = 'test-media-123';
      addLog('Call: uploadMedia()', { mediaId });
      const result = await MediaManager.uploadMedia(mediaId, { quality: 'high' });
      addLog('Result: uploadMedia()', result);
    } catch (error) {
      addLog('Error: uploadMedia()', null, error.message);
    }
  };

  // 测试删除媒体
  const testDeleteMedia = async () => {
    try {
      const mediaId = 'test-media-123';
      addLog('Call: deleteMedia()', { mediaId });
      const result = await MediaManager.deleteMedia(mediaId);
      addLog('Result: deleteMedia()', result);
    } catch (error) {
      addLog('Error: deleteMedia()', null, error.message);
    }
  };

  // 发送测试事件到 Native
  const sendTestEvent = () => {
    // 这里可以通过 Native Module 发送事件
    addLog('Send: Test Event', { data: testData });
    
    // 尝试解析 JSON
    try {
      const parsed = JSON.parse(testData);
      Alert.alert('发送测试数据', JSON.stringify(parsed, null, 2));
    } catch (e) {
      Alert.alert('发送测试数据', testData);
    }
  };

  // 测试批量调用
  const testBatchCalls = async () => {
    addLog('Start: Batch Test', null);
    
    // 连续调用多个方法
    await testGetMediaList();
    await new Promise(resolve => setTimeout(resolve, 500));
    await testSelectMedia();
  };

  // 模拟接收 Native 事件
  const simulateNativeEvent = () => {
    const mockData = {
      mediaId: `mock-${Date.now()}`,
      type: 'image',
      timestamp: Date.now(),
    };
    
    // 在 RN 端模拟事件（实际应该从 Native 接收）
    addLog('Simulate: Native Event', mockData);
    setEventCount(prev => prev + 1);
  };

  const testButtons = [
    { id: 'getList', title: '获取媒体列表', onPress: testGetMediaList },
    { id: 'select', title: '选择媒体', onPress: testSelectMedia },
    { id: 'upload', title: '上传媒体', onPress: testUploadMedia },
    { id: 'delete', title: '删除媒体', onPress: testDeleteMedia },
    { id: 'batch', title: '批量测试', onPress: testBatchCalls, highlight: true },
    { id: 'simulate', title: '模拟 Native 事件', onPress: simulateNativeEvent },
  ];

  return (
    <View style={CommonStyles.container}>
      {/* 头部 */}
      <View style={CommonStyles.header}>
        <TouchableOpacity onPress={onBack}>
          <Text style={styles.backButton}>← 返回</Text>
        </TouchableOpacity>
        <Text style={CommonStyles.headerTitle}>原生模块测试</Text>
        <View style={{width: 50}} />
      </View>

      <ScrollView style={CommonStyles.content}>
        {/* 状态卡片 */}
        <View style={CommonStyles.card}>
          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Module 可用:</Text>
            <Text style={[
              styles.statusValue,
              MediaManager.isAvailable() ? styles.success : styles.error
            ]}>
              {MediaManager.isAvailable() ? '✓ Yes' : '✗ No'}
            </Text>
          </View>
          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>事件接收:</Text>
            <Text style={styles.statusValue}>{eventCount} 个</Text>
          </View>
          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>日志条数:</Text>
            <Text style={styles.statusValue}>{logs.length} 条</Text>
          </View>
        </View>

        {/* 测试数据输入 */}
        <View style={CommonStyles.card}>
          <Text style={CommonStyles.subtitle}>测试数据</Text>
          <TextInput
            style={styles.input}
            value={testData}
            onChangeText={setTestData}
            multiline
            numberOfLines={3}
            placeholder="输入 JSON 数据..."
          />
          <TouchableOpacity
            style={[CommonStyles.button, {marginTop: 8}]}
            onPress={sendTestEvent}
          >
            <Text style={CommonStyles.buttonText}>发送测试数据</Text>
          </TouchableOpacity>
        </View>

        {/* 测试按钮组 */}
        <View style={CommonStyles.card}>
          <Text style={CommonStyles.subtitle}>API 测试</Text>
          <View style={styles.buttonGrid}>
            {testButtons.map(btn => (
              <TouchableOpacity
                key={btn.id}
                style={[
                  styles.testButton,
                  btn.highlight && styles.highlightButton
                ]}
                onPress={btn.onPress}
              >
                <Text style={[
                  styles.testButtonText,
                  btn.highlight && styles.highlightButtonText
                ]}>
                  {btn.title}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>

        {/* 日志 */}
        <View style={CommonStyles.card}>
          <View style={styles.logHeader}>
            <Text style={CommonStyles.subtitle}>调用日志</Text>
            <TouchableOpacity onPress={clearLogs}>
              <Text style={styles.clearButton}>清空</Text>
            </TouchableOpacity>
          </View>
          
          {logs.length === 0 ? (
            <Text style={styles.emptyText}>暂无日志</Text>
          ) : (
            logs.map(log => (
              <View key={log.id} style={styles.logItem}>
                <Text style={styles.logTime}>{log.timestamp}</Text>
                <Text style={[
                  styles.logAction,
                  log.error && styles.logError
                ]}>
                  {log.action}
                </Text>
                {log.result && (
                  <Text style={styles.logResult}>
                    {JSON.stringify(log.result).substring(0, 100)}
                    {JSON.stringify(log.result).length > 100 ? '...' : ''}
                  </Text>
                )}
                {log.error && (
                  <Text style={styles.logErrorText}>{log.error}</Text>
                )}
              </View>
            ))
          )}
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
  statusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: Colors.divider,
  },
  statusLabel: {
    fontSize: 14,
    color: Colors.textSecondary,
  },
  statusValue: {
    fontSize: 14,
    fontWeight: '600',
    color: Colors.text,
  },
  success: {
    color: Colors.success,
  },
  error: {
    color: Colors.error,
  },
  input: {
    borderWidth: 1,
    borderColor: Colors.border,
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    color: Colors.text,
    fontFamily: 'monospace',
    backgroundColor: Colors.background,
    minHeight: 80,
    textAlignVertical: 'top',
  },
  buttonGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  testButton: {
    backgroundColor: Colors.background,
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 8,
    minWidth: '48%',
    alignItems: 'center',
  },
  testButtonText: {
    fontSize: 14,
    color: Colors.text,
    fontWeight: '500',
  },
  highlightButton: {
    backgroundColor: Colors.primary,
  },
  highlightButtonText: {
    color: '#FFFFFF',
  },
  logHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  clearButton: {
    fontSize: 14,
    color: Colors.primary,
  },
  emptyText: {
    textAlign: 'center',
    color: Colors.textDisabled,
    paddingVertical: 20,
  },
  logItem: {
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: Colors.divider,
  },
  logTime: {
    fontSize: 11,
    color: Colors.textDisabled,
  },
  logAction: {
    fontSize: 13,
    fontWeight: '500',
    color: Colors.text,
    marginTop: 2,
  },
  logError: {
    color: Colors.error,
  },
  logResult: {
    fontSize: 11,
    color: Colors.textSecondary,
    marginTop: 2,
    fontFamily: 'monospace',
  },
  logErrorText: {
    fontSize: 11,
    color: Colors.error,
    marginTop: 2,
  },
});

export default NativeModuleTestScreen;
