iOS 端完善：
1. 检查 ShareUtils.ios.kt 是否有编译问题并修复
2. iOS 端 VideoPlayer 确认能播放后端视频流
3. iOS 端 SettingsStorage 确认 NSUserDefaults 正确读写
4. 检查所有 iosMain 文件是否有 API 不兼容
5. 确保 compileKotlinIosArm64 零错误零警告（expect/actual Beta 警告除外）
