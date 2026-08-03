package com.zuoyou.commentcollector.feature;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zuoyou.commentcollector.R;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 音乐播放器 — MediaPlayer 单例，管理 5 首歌曲的播放/暂停/切歌。
 * <p>
 * 封面联动：播放某首歌时通知监听器切换封面图。
 * 资源约定：
 * <ul>
 *   <li>封面：{@code cover_01} ~ {@code cover_05}（drawable）</li>
 *   <li>音频：{@code song_01} ~ {@code song_05}（raw）</li>
 * </ul>
 * 由用户提供实际资源文件。
 */
public class MusicPlayer {

    private static final String TAG = "MusicPlayer";
    private static final int SONG_COUNT = 5;

    /** 歌曲信息 */
    public static final class SongInfo {
        private final int index;       // 0-based
        private final String title;
        private final String artist;
        private final int coverResId;  // drawable
        private final int rawResId;    // raw

        public SongInfo(int index, String title, String artist, int coverResId, int rawResId) {
            this.index = index;
            this.title = title;
            this.artist = artist;
            this.coverResId = coverResId;
            this.rawResId = rawResId;
        }

        public int index()     { return index; }
        public String title()  { return title; }
        public String artist() { return artist; }
        public int coverResId() { return coverResId; }
        public int rawResId()  { return rawResId; }
    }

    /** 播放模式 */
    public enum PlayMode {
        SEQUENTIAL,   // 顺序播放
        RANDOM,       // 随机播放
        SINGLE_LOOP   // 单曲循环
    }

    /** 播放状态监听器 */
    public interface MusicListener {
        /** 当前歌曲变化（null 表示停止） */
        void onSongChanged(SongInfo song);
        /** 播放/暂停状态变化 */
        void onPlayStateChanged(boolean isPlaying);
        /** 播放模式变化 */
        default void onPlayModeChanged(PlayMode mode) {}
    }

    // ─── 单例 ───

    private static volatile MusicPlayer sInstance;

    public static MusicPlayer getInstance() {
        if (sInstance == null) {
            synchronized (MusicPlayer.class) {
                if (sInstance == null) {
                    sInstance = new MusicPlayer();
                }
            }
        }
        return sInstance;
    }

    // ─── 状态 ───

    private Context appContext;
    private Handler mainHandler;
    private MediaPlayer mediaPlayer;
    private final SongInfo[] songs = new SongInfo[SONG_COUNT];
    private int currentIndex = -1;  // -1 = 未选择
    private boolean initialized = false;
    private PlayMode currentMode = PlayMode.SEQUENTIAL;
    private final Random random = new Random();
    private final List<MusicListener> listeners = new CopyOnWriteArrayList<>();

    // ─── 异步播放状态 ───
    /** 每次 play() 递增，用于检测过期的 onPrepared 回调 */
    private int playGeneration = 0;
    /** prepareAsync 是否正在进行中（防止在未就绪时调用 start） */
    private boolean isPreparing = false;

    private MusicPlayer() {}

    // ─── 初始化 ───

    /**
     * 初始化播放器（在 MainActivity.onCreate 中调用一次）。
     */
    public synchronized void init(Context context) {
        if (initialized) return;
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        initSongList();
        initialized = true;
        Log.d(TAG, "初始化完成，歌曲数: " + SONG_COUNT);
    }

    public void addListener(MusicListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(MusicListener listener) {
        listeners.remove(listener);
    }

    // ─── 播放控制 ───

    /**
     * 加载并播放指定歌曲。
     *
     * @param songIndex 歌曲索引（0-based）
     */
    public synchronized void play(int songIndex) {
        if (!initialized || appContext == null) return;
        if (songIndex < 0 || songIndex >= SONG_COUNT) {
            Log.w(TAG, "无效歌曲索引: " + songIndex);
            return;
        }

        // 点击当前歌曲：暂停/恢复切换（仅在已就绪时）
        if (songIndex == currentIndex && mediaPlayer != null && !isPreparing) {
            if (mediaPlayer.isPlaying()) {
                pause();
            } else {
                resume();
            }
            return;
        }

        // 切歌：释放旧的
        releasePlayer();
        currentIndex = songIndex;
        final int generation = ++playGeneration;

        SongInfo song = songs[currentIndex];
        try {
            mediaPlayer = new MediaPlayer();
            // 关闭 AssetFileDescriptor 避免原生 fd 泄漏
            AssetFileDescriptor afd = appContext.getResources().openRawResourceFd(song.rawResId);
            try {
                mediaPlayer.setDataSource(afd);
            } finally {
                afd.close();
            }
            isPreparing = true;
            mediaPlayer.setOnPreparedListener(mp -> {
                // Post 到主线程 + 代际校验，防止已释放的 MediaPlayer 被误操作
                if (mainHandler != null) {
                    mainHandler.post(() -> {
                        synchronized (MusicPlayer.this) {
                            if (generation != playGeneration) return; // 过期回调
                            isPreparing = false;
                            Log.d(TAG, "MediaPlayer prepared，开始播放: " + song.title());
                            mp.start();
                            dispatchSongChanged(song);
                            dispatchPlayStateChanged(true);
                        }
                    });
                } else {
                    synchronized (MusicPlayer.this) {
                        if (generation != playGeneration) return;
                        isPreparing = false;
                        mp.start();
                        dispatchSongChanged(song);
                        dispatchPlayStateChanged(true);
                    }
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "播放完成: " + song.title());
                // Post to main looper — completion fires on MediaPlayer's internal thread,
                // but playNext() needs a Looper for MediaPlayer.create() on some device ROMs.
                if (mainHandler != null) {
                    mainHandler.post(() -> playNext());
                } else {
                    playNext();
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer 错误: what=" + what + ", extra=" + extra);
                synchronized (MusicPlayer.this) {
                    isPreparing = false;
                }
                releasePlayer();
                dispatchPlayStateChanged(false);
                return true;
            });
            mediaPlayer.prepareAsync();
            Log.d(TAG, "正在加载: " + song.title() + " - " + song.artist());
        } catch (Exception e) {
            Log.e(TAG, "创建 MediaPlayer 失败", e);
            isPreparing = false;
            releasePlayer();
        }
    }

    /**
     * 暂停当前播放。
     */
    public synchronized void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            Log.d(TAG, "暂停");
            dispatchPlayStateChanged(false);
        }
    }

    /**
     * 恢复播放。
     */
    public synchronized void resume() {
        if (mediaPlayer != null && !isPreparing && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            Log.d(TAG, "恢复播放");
            dispatchPlayStateChanged(true);
        }
    }

    /**
     * 停止播放并释放资源。
     */
    public synchronized void stop() {
        releasePlayer();
        currentIndex = -1;
        dispatchSongChanged(null);
        dispatchPlayStateChanged(false);
        Log.d(TAG, "停止");
    }

    /**
     * 切换播放/暂停。
     */
    public synchronized void togglePlayPause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            pause();
        } else if (isPreparing) {
            // 正在加载中，忽略
            Log.d(TAG, "MediaPlayer 加载中，忽略切换");
        } else if (currentIndex >= 0) {
            resume();
        } else {
            // 未选择过歌曲，播放第一首
            play(0);
        }
    }

    /**
     * 是否正在播放。
     */
    public synchronized boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    /**
     * 获取当前歌曲信息（未选择时返回 null）。
     */
    public SongInfo getCurrentSong() {
        if (currentIndex < 0) return null;
        return songs[currentIndex];
    }

    /**
     * 获取当前歌曲索引（-1 = 未选择）。
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /**
     * 获取所有歌曲信息。
     */
    public SongInfo[] getAllSongs() {
        return songs.clone();
    }

    // ─── 播放模式 ───

    /**
     * 切换播放模式：顺序 → 随机 → 单曲 → 顺序。
     */
    public synchronized void cyclePlayMode() {
        switch (currentMode) {
            case SEQUENTIAL -> currentMode = PlayMode.RANDOM;
            case RANDOM -> currentMode = PlayMode.SINGLE_LOOP;
            case SINGLE_LOOP -> currentMode = PlayMode.SEQUENTIAL;
        }
        Log.d(TAG, "播放模式: " + currentMode);
        dispatchPlayModeChanged(currentMode);
    }

    /**
     * 获取当前播放模式。
     */
    public synchronized PlayMode getPlayMode() {
        return currentMode;
    }

    /**
     * 播放下一首（根据播放模式决定）。
     */
    private synchronized void playNext() {
        if (currentIndex < 0) return;

        int nextIndex;
        switch (currentMode) {
            case SINGLE_LOOP:
                nextIndex = currentIndex;
                break;
            case RANDOM:
                do {
                    nextIndex = random.nextInt(SONG_COUNT);
                } while (nextIndex == currentIndex && SONG_COUNT > 1);
                break;
            case SEQUENTIAL:
            default:
                nextIndex = (currentIndex + 1) % SONG_COUNT;
                break;
        }

        Log.d(TAG, "自动下一首: " + songs[nextIndex].title() + " (模式=" + currentMode + ")");

        // 释放当前 MediaPlayer，重置索引，让 play() 认为是新歌
        releasePlayer();
        currentIndex = -1;
        play(nextIndex);
    }

    // ─── 生命周期 ───

    /**
     * 释放所有资源（Activity.onDestroy 或退出时调用）。
     */
    public synchronized void release() {
        releasePlayer();
        listeners.clear();
        initialized = false;
        sInstance = null;
        Log.d(TAG, "已释放");
    }

    // ─── 内部 ───

    private void initSongList() {
        // 歌曲元数据（标题和艺术家为占位，用户可修改）
        String[] titles = {"歌曲 1", "歌曲 2", "歌曲 3", "歌曲 4", "歌曲 5"};
        String[] artists = {"未知歌手", "未知歌手", "未知歌手", "未知歌手", "未知歌手"};

        int[] coverIds = {
                R.drawable.cover_01, R.drawable.cover_02, R.drawable.cover_03,
                R.drawable.cover_04, R.drawable.cover_05
        };
        int[] rawIds = {
                R.raw.song_01, R.raw.song_02, R.raw.song_03,
                R.raw.song_04, R.raw.song_05
        };

        for (int i = 0; i < SONG_COUNT; i++) {
            songs[i] = new SongInfo(i, titles[i], artists[i], coverIds[i], rawIds[i]);
        }
    }

    private void releasePlayer() {
        isPreparing = false;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "释放 MediaPlayer 异常", e);
            }
            mediaPlayer = null;
        }
    }

    private void dispatchSongChanged(SongInfo song) {
        for (MusicListener l : listeners) {
            l.onSongChanged(song);
        }
    }

    private void dispatchPlayStateChanged(boolean isPlaying) {
        for (MusicListener l : listeners) {
            l.onPlayStateChanged(isPlaying);
        }
    }

    private void dispatchPlayModeChanged(PlayMode mode) {
        for (MusicListener l : listeners) {
            l.onPlayModeChanged(mode);
        }
    }
}
