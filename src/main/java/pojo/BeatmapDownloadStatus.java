package pojo;

/**
 * 谱面 .osz 文件的异步下载状态，用于歌单页前端展示。
 * <ul>
 *   <li>{@link #PENDING}     — 等待下载</li>
 *   <li>{@link #DOWNLOADING} — 正在下载中</li>
 *   <li>{@link #DONE}        — 下载完成</li>
 *   <li>{@link #FAILED}      — 下载失败</li>
 * </ul>
 */
public enum BeatmapDownloadStatus {
    PENDING,
    DOWNLOADING,
    DONE,
    FAILED
}
