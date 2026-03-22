/**
 * React Native Test App
 * 
 * 测试与 rn-host / rn-android 的集成
 */

import React, {useEffect, useState} from 'react';
import {
  StyleSheet,
  View,
  Text,
  SafeAreaView,
  ScrollView,
  TouchableOpacity,
  Alert,
} from 'react-native';

// 导入原生模块
import MediaManager from './modules/MediaManager';

// 导入测试页面
import HomeScreen from './screens/HomeScreen';
import MediaGalleryScreen from './screens/MediaGalleryScreen';
import NativeModuleTestScreen from './screens/NativeModuleTestScreen';

const App = () => {
  const [currentScreen, setCurrentScreen] = useState('home');
  const [initialProps, setInitialProps] = useState({});

  useEffect(() => {
    // 获取从 Native 传递过来的初始属性
    if (global.__INITIAL_PROPS__) {
      setInitialProps(global.__INITIAL_PROPS__);
    }
  }, []);

  // 根据 moduleName 渲染不同页面
  const renderScreen = () => {
    switch (currentScreen) {
      case 'home':
        return <HomeScreen 
          initialProps={initialProps}
          onNavigate={setCurrentScreen}
        />;
      case 'gallery':
        return <MediaGalleryScreen 
          onBack={() => setCurrentScreen('home')}
        />;
      case 'nativeTest':
        return <NativeModuleTestScreen 
          onBack={() => setCurrentScreen('home')}
        />;
      default:
        return <HomeScreen onNavigate={setCurrentScreen} />;
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      {renderScreen()}
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
});

export default App;
