package pojo;

import java.util.Objects;

/**
 * 歌曲实体类，封装一首用户通过弹幕点播的 osu! 谱面信息。
 * <p>
 * 每个 Song 以 {@link #beatMapId} 作为唯一标识，
 * 两个 Song 相等当且仅当它们的 beatMapId 相同（见 {@link #equals}/{@link #hashCode}）。
 * </p>
 * <p>
 * 下载状态 {@link #downloadStatus} 和本地路径 {@link #downloadLocalPath}
 * 由后台下载线程异步更新，因此声明为 {@code volatile} 以保证可见性。
 * </p>
 */
public class Song {

    /** 歌曲标题，来自 Sayobot API 查询结果 */
    private String songTitle;

    /** 发起点歌的用户名（B站弹幕用户） */
    private String songRequester;

    /** osu! 谱面集 ID（Sayobot sid） */
    private int beatMapId;

    /** 异步下载状态，由后台线程更新，volatile 保证可见性 */
    private volatile BeatmapDownloadStatus downloadStatus = BeatmapDownloadStatus.PENDING;

    /** 本机 .osz 文件绝对路径，仅在 {@link BeatmapDownloadStatus#DONE} 时有值 */
    private volatile String downloadLocalPath;

    /**
     * 创建一首待点播歌曲。
     *
     * @param songRequester 点歌的弹幕用户名
     * @param beatMapId     osu! 谱面集 ID
     */
    public Song(String songRequester, int beatMapId) {
        this.songRequester = songRequester;
        this.beatMapId = beatMapId;
    }

    /**
     * 基于 beatMapId 判断两首歌是否为同一首。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Song song = (Song) o;
        return beatMapId == song.beatMapId;
    }

    /**
     * 与 {@link #equals} 一致，仅基于 beatMapId 计算哈希。
     */
    @Override
    public int hashCode() {
        return Objects.hash(beatMapId);
    }

    public String getSongTitle() {
        return songTitle;
    }

    public void setSongTitle(String songTitle) {
        this.songTitle = songTitle;
    }

    public String getSongRequester() {
        return songRequester;
    }

    public void setSongRequester(String songRequester) {
        this.songRequester = songRequester;
    }

    public int getBeatMapId() {
        return beatMapId;
    }

    public void setBeatMapId(int beatMapId) {
        this.beatMapId = beatMapId;
    }

    public BeatmapDownloadStatus getDownloadStatus() {
        return downloadStatus;
    }

    /**
     * 设置下载状态；传入 null 时自动回退为 {@link BeatmapDownloadStatus#PENDING}。
     */
    public void setDownloadStatus(BeatmapDownloadStatus downloadStatus) {
        this.downloadStatus = downloadStatus != null ? downloadStatus : BeatmapDownloadStatus.PENDING;
    }

    public String getDownloadLocalPath() {
        return downloadLocalPath;
    }

    public void setDownloadLocalPath(String downloadLocalPath) {
        this.downloadLocalPath = downloadLocalPath;
    }
}
