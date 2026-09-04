package com.lolstudio.lizhimusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 李志音乐 播放服务：前台通知 + MediaPlayer 队列播放。
 * 打开 App 即自动播放；支持 播放/暂停、上一首、下一首、拖动进度、
 * 列表/单曲循环、随机、退出；兼容车机媒体按键(ACTION_MEDIA_BUTTON)。
 */
public class MusicService extends Service {

    public static final String ACTION_PLAY_PAUSE = "com.lolstudio.lizhimusic.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.lolstudio.lizhimusic.NEXT";
    public static final String ACTION_PREV = "com.lolstudio.lizhimusic.PREV";
    public static final String ACTION_PLAY_AT = "com.lolstudio.lizhimusic.PLAY_AT";
    public static final String ACTION_EXIT = "com.lolstudio.lizhimusic.EXIT";
    public static final String ACTION_ENSURE = "com.lolstudio.lizhimusic.ENSURE";

    public static final String ACTION_STATE = "com.lolstudio.lizhimusic.STATE";
    public static final String EXTRA_STATE = "state";      // init / playing / paused / error
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_MSG = "msg";
    public static final String EXTRA_INDEX_REQ = "index_req";

    /** 曲目显示名，与 assets/songs/trackNN.mp3 一一对应 */
    public static final String[] TRACK_NAMES = {
            "米店", "关于郑州的记忆", "你离开了南京，从此没有人和我说话", "热河",
            "杭州", "山阴路的夏天", "梵高先生", "墙上的向日葵", "广场", "定西",
            "结婚", "忽然", "尽头", "人民不需要自由", "和你在一起", "妈妈",
            "天空之城", "这个世界会好吗"
    };

    private static final String CHANNEL_ID = "lizhi_play";
    private static final int NOTIF_ID = 1;

    private final IBinder mBinder = new LocalBinder();
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private MediaPlayer mPlayer;
    private final List<File> mFiles = new ArrayList<>();
    private int mIndex = -1;
    private boolean mPrepared = false;
    private boolean mInitialized = false;   // 曲库拷贝完成
    private boolean mShuffle = false;
    private boolean mRepeatOne = false;
    private final Random mRandom = new Random();
    private AudioManager mAudio;
    private NotificationManager mNM;

    private final BroadcastReceiver mMediaKeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
            android.view.KeyEvent ev =
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (ev == null || ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return;
            switch (ev.getKeyCode()) {
                case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case android.view.KeyEvent.KEYCODE_HEADSETHOOK:
                    playPause();
                    abortBroadcast();
                    break;
                case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                    next();
                    abortBroadcast();
                    break;
                case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    prev();
                    abortBroadcast();
                    break;
                default:
                    break;
            }
        }
    };

    public class LocalBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mAudio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            mNM.createNotificationChannel(ch);
        }
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            registerReceiver(mMediaKeyReceiver, f);
        } catch (Exception ignored) {
        }
        startForeground(NOTIF_ID, buildNotification(false));
        ensureInitialized();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_EXIT.equals(action)) {
            exitApp();
            return START_NOT_STICKY;
        }
        boolean ensureLike = intent == null || action == null
                || ACTION_ENSURE.equals(action);
        if (ensureLike && mInitialized && mPlayer == null) {
            // 进程被系统回收后重启 / 重新打开 App，恢复自动播放
            playAt(mIndex < 0 ? 0 : mIndex);
        }
        if (ACTION_PLAY_PAUSE.equals(action)) playPause();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREV.equals(action)) prev();
        else if (ACTION_PLAY_AT.equals(action)) {
            int i = intent.getIntExtra(EXTRA_INDEX_REQ, 0);
            if (i >= 0 && i < TRACK_NAMES.length) playAt(i);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mMediaKeyReceiver);
        } catch (Exception ignored) {
        }
        releasePlayer();
    }

    // ---------------- 曲库初始化：assets -> 应用外部目录 ----------------

    private File musicDir() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File dir = new File(base, "music");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void ensureInitialized() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = musicDir();
                    File marker = new File(dir, ".ready_v" +
                            1); // 版本号随曲目更新
                    if (!marker.exists()) {
                        AssetManager am = getAssets();
                        mFiles.clear();
                        for (int i = 0; i < TRACK_NAMES.length; i++) {
                            String name = String.format("track%02d.mp3", i + 1);
                            File out = new File(dir, name);
                            if (!out.exists() || out.length() <= 0) {
                                copy(am, "songs/" + name, out);
                            }
                            broadcastInit((i + 1) + "/" + TRACK_NAMES.length);
                            mFiles.add(out);
                        }
                        //noinspection ResultOfMethodCallIgnored
                        marker.createNewFile();
                    } else {
                        mFiles.clear();
                        for (int i = 0; i < TRACK_NAMES.length; i++) {
                            File out = new File(dir, String.format("track%02d.mp3", i + 1));
                            if (out.exists() && out.length() > 0) mFiles.add(out);
                        }
                    }
                    if (mFiles.size() != TRACK_NAMES.length) {
                        // 目录被清理过，重新拷贝一遍
                        marker.delete();
                        broadcastInit("曲库缺失，重新加载…");
                        ensureInitialized();
                        return;
                    }
                    mInitialized = true;
                    broadcastState("ready", null);
                    // 打开即自动播放
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mIndex < 0) {
                                playAt(mShuffle ? mRandom.nextInt(mFiles.size()) : 0);
                            } else if (mPlayer == null) {
                                playAt(mIndex);
                            }
                        }
                    });
                } catch (Exception e) {
                    broadcastState("error", "加载曲库失败: " + e.getMessage());
                }
            }
        }, "lizhi-init").start();
    }

    private static void copy(AssetManager am, String assetPath, File out) throws Exception {
        InputStream in = am.open(assetPath);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.flush();
        os.close();
        in.close();
    }

    // ---------------- 播放控制 ----------------

    public void playAt(int index) {
        if (!mInitialized || index < 0 || index >= mFiles.size()) return;
        releasePlayer();
        mIndex = index;
        mPrepared = false;
        try {
            mPlayer = new MediaPlayer();
            mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mPlayer.setDataSource(mFiles.get(index).getAbsolutePath());
            mPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mPrepared = true;
                    requestFocus();
                    mp.start();
                    broadcastState("playing", null);
                    updateNotification(true);
                }
            });
            mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (mRepeatOne) {
                        playAt(mIndex);
                    } else {
                        next();
                    }
                }
            });
            mPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    broadcastState("error", "无法播放《" + TRACK_NAMES[mIndex] + "》");
                    releasePlayer();
                    updateNotification(false);
                    return true;
                }
            });
            mPlayer.prepareAsync();
            broadcastState("loading", TRACK_NAMES[index]);
        } catch (Exception e) {
            broadcastState("error", "打开失败: " + e.getMessage());
            releasePlayer();
        }
    }

    public void playPause() {
        if (!mInitialized) return;
        if (mPlayer == null) {
            playAt(mIndex < 0 ? 0 : mIndex);
            return;
        }
        if (!mPrepared) return;
        if (mPlayer.isPlaying()) {
            mPlayer.pause();
            broadcastState("paused", null);
            updateNotification(false);
        } else {
            requestFocus();
            mPlayer.start();
            broadcastState("playing", null);
            updateNotification(true);
        }
    }

    public void next() {
        playAt(nextIndexFrom(mIndex));
    }

    public void prev() {
        if (mPlayer != null && mPrepared && mPlayer.getCurrentPosition() > 3000) {
            mPlayer.seekTo(0);
            return;
        }
        int n = mFiles.size();
        if (n == 0) return;
        int i = mIndex <= 0 ? n - 1 : mIndex - 1;
        if (mShuffle && n > 1) i = randomOtherIndex(mIndex);
        playAt(i);
    }

    private int nextIndexFrom(int cur) {
        int n = mFiles.size();
        if (n == 0) return 0;
        if (mShuffle && n > 1) return randomOtherIndex(cur);
        return (cur + 1) % n;
    }

    private int randomOtherIndex(int cur) {
        int i = cur;
        int n = mFiles.size();
        while (i == cur && n > 1) i = mRandom.nextInt(n);
        return i;
    }

    public void seekTo(int ms) {
        if (mPlayer != null && mPrepared) {
            mPlayer.seekTo(ms);
        }
    }

    public void setShuffle(boolean on) {
        mShuffle = on;
    }

    public void setRepeatOne(boolean on) {
        mRepeatOne = on;
    }

    // ---------------- 状态查询 ----------------

    public boolean isInitialized() {
        return mInitialized;
    }

    public boolean isPlaying() {
        try {
            return mPlayer != null && mPrepared && mPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public int getCurrentIndex() {
        return mIndex;
    }

    public int getPosition() {
        try {
            return (mPlayer != null && mPrepared) ? mPlayer.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDuration() {
        try {
            return (mPlayer != null && mPrepared) ? mPlayer.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isShuffle() {
        return mShuffle;
    }

    public boolean isRepeatOne() {
        return mRepeatOne;
    }

    // ---------------- 退出 ----------------

    private void exitApp() {
        releasePlayer();
        mIndex = -1;
        mNM.cancel(NOTIF_ID);
        stopForeground(true);
        stopSelf();
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            try {
                mPlayer.reset();
                mPlayer.release();
            } catch (Exception ignored) {
            }
            mPlayer = null;
        }
        mPrepared = false;
    }

    // ---------------- 通知 & 广播 ----------------

    private void requestFocus() {
        try {
            mAudio.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } catch (Exception ignored) {
        }
    }

    private void broadcastState(String state, String msg) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_INDEX, mIndex);
        if (msg != null) i.putExtra(EXTRA_MSG, msg);
        sendBroadcast(i);
    }

    private void broadcastInit(String progress) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, "init");
        i.putExtra(EXTRA_MSG, progress);
        sendBroadcast(i);
    }

    private PendingIntent pi(String action) {
        Intent i = new Intent(this, MusicService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, action.hashCode(), i, flags);
    }

    private Notification buildNotification(boolean playing) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, piFlags);

        String title = mIndex >= 0 && mIndex < TRACK_NAMES.length
                ? TRACK_NAMES[mIndex] : getString(R.string.app_name);
        String sub = mInitialized ? getString(R.string.artist) : getString(R.string.initializing);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
                .setContentText(sub)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_previous, "上一首", pi(ACTION_PREV))
                .addAction(playing ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play,
                        playing ? "暂停" : "播放", pi(ACTION_PLAY_PAUSE))
                .addAction(android.R.drawable.ic_media_next, "下一首", pi(ACTION_NEXT))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.exit), pi(ACTION_EXIT));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        return b.build();
    }

    private void updateNotification(boolean playing) {
        mNM.notify(NOTIF_ID, buildNotification(playing));
    }
}
