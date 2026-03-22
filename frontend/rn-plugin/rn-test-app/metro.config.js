const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('metro-config').MetroConfig}
 */
const config = {
  // 配置 watch 文件夹，避免监听不必要的文件
  watchFolders: [
    __dirname,
  ],
  
  // 配置 resolver
  resolver: {
    // 确保能找到 react-native 模块
    nodeModulesPaths: [
      __dirname + '/node_modules',
    ],
    // 黑名单，避免 metro 处理不必要的文件
    blacklistRE: /rn-plugin\/(rn-host|rn-android|scripts|output)\/.*/,
  },
  
  // 服务器配置
  server: {
    // 端口配置
    port: 8081,
  },
  
  // 缓存配置
  cacheStores: [],
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
