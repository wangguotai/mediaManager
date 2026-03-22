/**
 * 复制 bundle 文件到 Android 项目
 * 
 * 这个脚本将打包好的 bundle 文件复制到 rn-host 模块的 assets 目录
 * 以便在 AAR 中包含 bundle
 */

const fs = require('fs');
const path = require('path');

// 路径配置
const bundlePath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'assets', 'index.android.bundle');
const assetsDestPath = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'res');

// 目标路径：rn-host 模块
const rnHostAssetsPath = path.join(__dirname, '..', '..', 'rn-host', 'src', 'main', 'assets');
const rnHostBundlePath = path.join(rnHostAssetsPath, 'index.android.bundle');

console.log('=== React Native Bundle Copy Script ===\n');

// 检查 bundle 是否存在
if (!fs.existsSync(bundlePath)) {
  console.error('❌ Bundle file not found!');
  console.log('Please run: npm run bundle:android');
  process.exit(1);
}

console.log('📦 Bundle found:', bundlePath);

// 确保目标目录存在
if (!fs.existsSync(rnHostAssetsPath)) {
  console.log('📁 Creating assets directory:', rnHostAssetsPath);
  fs.mkdirSync(rnHostAssetsPath, { recursive: true });
}

// 复制 bundle
try {
  fs.copyFileSync(bundlePath, rnHostBundlePath);
  console.log('✅ Bundle copied to:', rnHostBundlePath);
} catch (error) {
  console.error('❌ Failed to copy bundle:', error.message);
  process.exit(1);
}

// 检查是否有 drawable 资源
const drawablePath = path.join(assetsDestPath, 'drawable-*');
console.log('\n📋 Next steps:');
console.log('1. Build rn-host AAR: ./gradlew :rn-plugin:rn-host:assembleRelease');
console.log('2. Or run: ./gradlew :rn-plugin:rn-host:publishAar');
console.log('\n✨ Done!');
