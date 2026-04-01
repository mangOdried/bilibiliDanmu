package pojo;

import java.util.Objects;

public class Song {
    private String songTitle;
    private String songRequester;
    private int beatMapId;
    private volatile BeatmapDownloadStatus downloadStatus = BeatmapDownloadStatus.PENDING;
    private volatile String downloadLocalPath;

    public Song(String songRequester, int beatMapId) {
        this.songRequester = songRequester;
        this.beatMapId = beatMapId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Song song = (Song) o;
        return beatMapId == song.beatMapId;
    }

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
