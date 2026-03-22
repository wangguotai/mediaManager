/**
 * 首页 - 测试入口
 */

import React from 'react';
import {
  View,
  Text,
  ScrollView,
  TouchableOpacity,
  StyleSheet,
} from 'react-native';
import Colors from '../utils/Colors';
import CommonStyles from '../utils/Styles';
import MediaManager from '../modules/MediaManager';

const HomeScreen = ({initialProps = {}, onNavigate}) => {
  const isNativeAvailable = MediaManager.isAvailable();

  const menuItems = [
    {
      id: 'gallery',
      title: '📷 媒体库测试',
      description: '测试媒体选择、浏览功能',
      onPress: () => onNavigate('gallery'),
    },
    {
      id: 'native',
      title: '🔌 原生模块测试',
      description: '测试 Native Module 调用',
      onPress: () => onNavigate('nativeTest'),
    },
  ];

  return (
    <ScrollView style={CommonStyles.container}>
      <View style={styles.header}>
        <Text style={CommonStyles.title}>RN Test App</Text>
        <Text style={CommonStyles.body}>
          React Native 与 rn-host/rn-android 集成测试
        </Text>
        
        {/* 状态指示器 */}
        <View style={styles.statusContainer}>
          <Text style={styles.statusLabel}>Native Module 状态:</Text>
          <View style={[styles.statusBadge, isNativeAvailable ? styles.statusSuccess : styles.statusError]}>
            <Text style={styles.statusText}>
              {isNativeAvailable ? '已连接' : '未连接'}
            </Text>
          </View>
        </View>

        {/* 显示从 Native 传递的初始属性 */}
        {Object.keys(initialProps).length > 0 && (
          <View style={styles.propsContainer}>
            <Text style={styles.propsTitle}>从 Native 传递的属性:</Text>
            <Text style={styles.propsText}>
              {JSON.stringify(initialProps, null, 2)}
            </Text>
          </View>
        )}
      </View>

      <View style={styles.menuContainer}>
        <Text style={CommonStyles.subtitle}>测试项目</Text>
        {menuItems.map(item => (
          <TouchableOpacity
            key={item.id}
            style={CommonStyles.card}
            onPress={item.onPress}
            activeOpacity={0.7}
          >
            <Text style={styles.menuTitle}>{item.title}</Text>
            <Text style={styles.menuDescription}>{item.description}</Text>
          </TouchableOpacity>
        ))}
      </View>

      <View style={styles.footer}>
        <Text style={CommonStyles.caption}>
          RN Version: 0.82.1{'\n'}
          Module: RNTTestApp
        </Text>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  header: {
    padding: 20,
    backgroundColor: Colors.surface,
    borderBottomWidth: 1,
    borderBottomColor: Colors.divider,
  },
  statusContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 12,
  },
  statusLabel: {
    fontSize: 14,
    color: Colors.textSecondary,
    marginRight: 8,
  },
  statusBadge: {
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 16,
  },
  statusSuccess: {
    backgroundColor: '#E8F5E9',
  },
  statusError: {
    backgroundColor: '#FFEBEE',
  },
  statusText: {
    fontSize: 12,
    fontWeight: '600',
  },
  propsContainer: {
    marginTop: 16,
    padding: 12,
    backgroundColor: Colors.background,
    borderRadius: 8,
  },
  propsTitle: {
    fontSize: 12,
    fontWeight: '600',
    color: Colors.textSecondary,
    marginBottom: 8,
  },
  propsText: {
    fontSize: 12,
    color: Colors.text,
    fontFamily: 'monospace',
  },
  menuContainer: {
    padding: 16,
  },
  menuTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: Colors.text,
    marginBottom: 4,
  },
  menuDescription: {
    fontSize: 14,
    color: Colors.textSecondary,
  },
  footer: {
    padding: 20,
    alignItems: 'center',
  },
});

export default HomeScreen;
