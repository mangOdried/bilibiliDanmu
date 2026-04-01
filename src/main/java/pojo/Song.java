package pojo;

import java.util.Objects;

public class Song {
    private String songTitle;    // 歌曲名
    private String songRequester;// 点歌的用户名
    private Integer beatMapId;    // 铺面ID
    private Boolean isRequested  = false; // 是否已经被播放
    /** 异步下载状态，由后台线程更新 */
    private volatile BeatmapDownloadStatus downloadStatus = BeatmapDownloadStatus.PENDING;
    /** 本机 .osz 绝对路径，仅 DONE 时有值 */
    private volatile String downloadLocalPath;

    public Song( String songRequester, int beatMapId ) {
        this.songRequester = songRequester;
        this.beatMapId = beatMapId;
    }

    public BeatmapDownloadStatus getDownloadStatus() {
        return downloadStatus;
    }

    public void setDownloadStatus(BeatmapDownloadStatus downloadStatus) {
        this.downloadStatus = downloadStatus != null ? downloadStatus : BeatmapDownloadStatus.PENDING;
    }

    public String getDownloadLocalPath() {
        return downloadLocalPath;
    }

    public void setDownloadLocalPath(String downloadLocalPath) {
        this.downloadLocalPath = downloadLocalPath;
    }

    // 重写 equals 方法：定义“相等”的逻辑
    @Override
    public boolean equals(Object o) {
        if (this == o) return true; // 同一对象
        if (o == null || getClass() != o.getClass()) return false;
        Song song = (Song) o;
        return Objects.equals(beatMapId, song.beatMapId) ;
    }

    // 重写 hashCode 方法：用于 HashSet 的快速定位
    @Override
    public int hashCode() {
        return Objects.hash(beatMapId);
    }
    public Boolean getValid() {
        return valid;
    }

    public void setValid(Boolean valid) {
        this.valid = valid;
    }

    private Boolean valid;       // 是否是有效点歌（例如歌曲不存在时设为 false）

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

    public Boolean getRequested() {
        return isRequested;
    }

    public void setRequested(Boolean requested) {
        isRequested = requested;
    }
}
