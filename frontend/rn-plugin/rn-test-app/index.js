/**
 * React Native Test App 入口文件
 * 
 * 注册以下模块供 rn-host 加载：
 * - MediaGallery: 媒体库测试页面
 * - TestHome: 测试主页
 * - NativeModuleTest: 原生模块测试页面
 */

import {AppRegistry} from 'react-native';
import App from './src/App';
import {name as appName} from './app.json';

// 注册主应用
AppRegistry.registerComponent(appName, () => App);

// 注册其他模块（供 rn-host 动态加载）
AppRegistry.registerComponent('MediaGallery', () => App);
AppRegistry.registerComponent('TestHome', () => App);
AppRegistry.registerComponent('NativeModuleTest', () => App);

// 导出模块名称常量
export const MODULE_NAMES = {
  MAIN: appName,
  MEDIA_GALLERY: 'MediaGallery',
  TEST_HOME: 'TestHome',
  NATIVE_MODULE_TEST: 'NativeModuleTest',
};
