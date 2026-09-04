package com.lolstudio.lizhimusic;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * 在线曲库：流播 lizhi-fgek.pages.dev 同源歌单（291首，jsdelivr CDN）。
 * 布局：左侧播放控制，右侧播放列表。
 */
public class OnlinePlayerActivity extends Activity
        implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnBufferingUpdateListener {

    private static final String SONGS_ASSET = "songs_online.json";

    private final ArrayList<String> mNames = new ArrayList<>();
    private final ArrayList<String> mAlbums = new ArrayList<>();
    private final ArrayList<String> mUrls = new ArrayList<>();

    private MediaPlayer mPlayer;
    private int mIndex = -1;
    private boolean mPrepared;
    private boolean mResumeOnPrepared;
    private boolean mUserSeeking;

    private TextView tvTitle, tvAlbum, tvStatus, tvPos, tvDur;
    private SeekBar seekBar;
    private ImageButton btnPlay;
    private ListView listSongs;
    private BaseAdapter adapter;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            mHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_online);

        if (!loadSongs()) {
            Toast.makeText(this, "在线曲库加载失败", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tvTitle = findViewById(R.id.tvOnlineTitle);
        tvAlbum = findViewById(R.id.tvOnlineAlbum);
        tvStatus = findViewById(R.id.tvOnlineStatus);
        tvPos = findViewById(R.id.tvOnlinePos);
        tvDur = findViewById(R.id.tvOnlineDur);
        seekBar = findViewById(R.id.seekOnlineBar);
        btnPlay = findViewById(R.id.btnOnlinePlay);
        listSongs = findViewById(R.id.listOnlineSongs);

        adapter = new OnlineAdapter();
        listSongs.setAdapter(adapter);
        listSongs.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                playAt(position);
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mUserSeeking = false;
                if (mPrepared) mPlayer.seekTo(seekBar.getProgress());
            }
        });

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        findViewById(R.id.btnOnlinePrev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playAt(mIndex <= 0 ? mNames.size() - 1 : mIndex - 1);
            }
        });
        findViewById(R.id.btnOnlineNext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playAt((mIndex + 1) % mNames.size());
            }
        });
        findViewById(R.id.btnOnlineBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnOnlineExit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                releasePlayer();
                startService(new Intent(OnlinePlayerActivity.this, MusicService.class)
                        .setAction(MusicService.ACTION_EXIT));
                finishAffinity();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });

        tvAlbum.setText("共 " + mNames.size() + " 首 · 在线流播");
        tvStatus.setText("选择右侧歌曲开始播放");
        mHandler.post(mTicker);
    }

    private boolean loadSongs() {
        InputStream in = null;
        try {
            in = getAssets().open(SONGS_ASSET);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                mNames.add(o.getString("name"));
                mAlbums.add(o.getString("artist"));
                mUrls.add(o.getString("url"));
            }
            return mUrls.size() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void playAt(int index) {
        if (index < 0 || index >= mUrls.size()) return;
        mIndex = index;
        mPrepared = false;
        mResumeOnPrepared = true;
        releasePlayer();
        mPlayer = new MediaPlayer();
        mPlayer.setOnPreparedListener(this);
        mPlayer.setOnCompletionListener(this);
        mPlayer.setOnErrorListener(this);
        mPlayer.setOnBufferingUpdateListener(this);
        try {
            mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mPlayer.setDataSource(mUrls.get(index));
            mPlayer.prepareAsync();
        } catch (Exception e) {
            tvStatus.setText("载入失败");
            mPlayer.release();
            mPlayer = null;
            return;
        }
        tvTitle.setText(mNames.get(index));
        tvAlbum.setText(mAlbums.get(index));
        tvStatus.setText("正在载入《" + mNames.get(index) + "》…");
        btnPlay.setImageResource(android.R.drawable.ic_media_pause);
        listSongs.setSelection(index);
        adapter.notifyDataSetChanged();
    }

    private void togglePlay() {
        if (mPlayer == null || !mPrepared) {
            if (mIndex >= 0) playAt(mIndex);
            return;
        }
        if (mPlayer.isPlaying()) {
            mPlayer.pause();
            tvStatus.setText("已暂停");
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
        } else {
            mPlayer.start();
            tvStatus.setText("正在播放");
            btnPlay.setImageResource(android.R.drawable.ic_media_pause);
        }
    }

    private void updateProgress() {
        if (mPlayer == null || !mPrepared || mUserSeeking) return;
        int dur = mPlayer.getDuration();
        int pos = mPlayer.getCurrentPosition();
        seekBar.setMax(dur > 0 ? dur : 1);
        seekBar.setProgress(pos);
        tvPos.setText(fmt(pos));
        tvDur.setText(fmt(dur));
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            try {
                mPlayer.release();
            } catch (Exception ignored) {
            }
            mPlayer = null;
        }
        mPrepared = false;
    }

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mPrepared = true;
        if (mResumeOnPrepared) {
            mp.start();
            tvStatus.setText("正在播放");
            btnPlay.setImageResource(android.R.drawable.ic_media_pause);
        }
        updateProgress();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        playAt((mIndex + 1) % mNames.size());
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        tvStatus.setText("播放失败，点击下一首或重选歌曲");
        btnPlay.setImageResource(android.R.drawable.ic_media_play);
        Toast.makeText(this, "播放失败（网络或格式问题）", Toast.LENGTH_SHORT).show();
        mPrepared = false;
        return true;
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        seekBar.setSecondaryProgress(seekBar.getMax() * percent / 100);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mTicker);
        releasePlayer();
    }

    /** 播放列表适配器：歌名 + 专辑两行，当前曲目高亮 */
    private class OnlineAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mNames.size();
        }

        @Override
        public Object getItem(int position) {
            return mNames.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(OnlinePlayerActivity.this);
                tv.setTextSize(17f);
                tv.setPadding(24, 26, 24, 26);
            }
            boolean isCur = position == mIndex;
            SpannableStringBuilder sb = new SpannableStringBuilder();
            String head = (isCur ? "♪  " : (position + 1) + ".  ") + mNames.get(position) + "\n";
            sb.append(head);
            int albumStart = sb.length();
            sb.append("    " + mAlbums.get(position));
            sb.setSpan(new RelativeSizeSpan(0.72f), albumStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFFAAAAAA), albumStart, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(sb);
            tv.setTextColor(isCur ? 0xFFE6B342 : 0xFFFFFFFF);
            tv.setTypeface(null, isCur
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            return tv;
        }
    }
}
