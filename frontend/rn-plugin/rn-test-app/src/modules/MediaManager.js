/**
 * MediaManager Native Module 封装
 * 
 * 对应 rn-android 中的 MediaManagerModule.kt
 */

import {NativeModules, NativeEventEmitter, Platform} from 'react-native';

const {MediaManager} = NativeModules;

// 创建事件发射器
const mediaManagerEmitter = MediaManager 
  ? new NativeEventEmitter(MediaManager) 
  : null;

/**
 * MediaManager API 封装类
 */
class MediaManagerAPI {
  /**
   * 检查 MediaManager 是否可用
   */
  static isAvailable() {
    return !!MediaManager;
  }

  /**
   * 获取媒体列表
   * @returns {Promise<Array>} 媒体列表
   */
  static async getMediaList() {
    if (!MediaManager) {
      throw new Error('MediaManager native module is not available');
    }
    return await MediaManager.getMediaList();
  }

  /**
   * 选择媒体
   * @param {Object} options 选项
   * @param {string} options.mediaType - 媒体类型: 'image', 'video', 'all'
   * @param {number} options.maxCount - 最大选择数量
   * @returns {Promise<Object>}
   */
  static async selectMedia(options = {}) {
    if (!MediaManager) {
      throw new Error('MediaManager native module is not available');
    }
    return await MediaManager.selectMedia({
      mediaType: options.mediaType || 'all',
      maxCount: options.maxCount || 1,
    });
  }

  /**
   * 上传媒体
   * @param {string} mediaId 媒体ID
   * @param {Object} options 上传选项
   * @returns {Promise<Object>}
   */
  static async uploadMedia(mediaId, options = {}) {
    if (!MediaManager) {
      throw new Error('MediaManager native module is not available');
    }
    return await MediaManager.uploadMedia(mediaId, options);
  }

  /**
   * 删除媒体
   * @param {string} mediaId 媒体ID
   * @returns {Promise<Object>}
   */
  static async deleteMedia(mediaId) {
    if (!MediaManager) {
      throw new Error('MediaManager native module is not available');
    }
    return await MediaManager.deleteMedia(mediaId);
  }

  /**
   * 监听媒体选择事件
   * @param {Function} callback 回调函数
   * @returns {Object} 订阅对象，用于取消监听
   */
  static onMediaSelected(callback) {
    if (!mediaManagerEmitter) {
      console.warn('MediaManager event emitter is not available');
      return {remove: () => {}};
    }
    return mediaManagerEmitter.addListener('onMediaSelected', callback);
  }

  /**
   * 监听媒体上传事件
   * @param {Function} callback 回调函数
   * @returns {Object} 订阅对象
   */
  static onMediaUploaded(callback) {
    if (!mediaManagerEmitter) {
      console.warn('MediaManager event emitter is not available');
      return {remove: () => {}};
    }
    return mediaManagerEmitter.addListener('onMediaUploaded', callback);
  }

  /**
   * 移除所有监听器
   */
  static removeAllListeners() {
    if (mediaManagerEmitter) {
      mediaManagerEmitter.removeAllListeners('onMediaSelected');
      mediaManagerEmitter.removeAllListeners('onMediaUploaded');
    }
  }
}

export default MediaManagerAPI;

// 也导出原始模块，便于直接使用
export {MediaManager, mediaManagerEmitter};
