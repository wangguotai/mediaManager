package com.wgt.media

/**
 * 平台无关的"私有文件持久化"抽象（expect/actual）。
 *
 * 用途：增量同步 cursor、上传去重 manifest（sha256 集合）、离线上传队列的落地存储。
 * 这些数据量小、结构稳定，用 JSON 文本落盘到 App 私有目录即可，无需引入 SQLDelight
 * 等重依赖（后者会改动 Gradle 并要求 iOS 额外 framework，与"零新依赖、编译稳"取舍冲突）。
 *
 * 语义：[read] 返回文件全部文本（文件不存在 / 读失败返回 null）；[write] 原子地用
 * [content] 覆盖文件（平台实现保证写完整）。各 actual 在 App 私有可写目录下以 [name]
 * 为文件名读写，跨进程安全（仅本 App 可访问）。
 *
 * 线程安全：write 内部串行写盘；读不持有状态。调用方若需并发顺序，应在更高层加锁
 * （见 [SyncStateStore] / [DedupStore] / [OfflineQueueStore] 各自的同步控制）。
 */
expect object PersistentFileStore {

    /**
     * 读取 [name] 文件的全部文本。文件不存在或读取异常返回 null（调用方视为"空"）。
     */
    fun read(name: String): String?

    /**
     * 以 [content] 覆盖写入 [name] 文件。文件不存在则创建；写失败仅记录日志、不抛出
     * （保证弱网/磁盘异常不使入队操作崩溃）。
     */
    fun write(name: String, content: String)
}
