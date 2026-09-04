package com.lolstudio.lizhimusic;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 李志音乐 主界面：打开即自动播放，播控 + 曲库列表 + 退出。
 */
public class MainActivity extends Activity {

    private MusicService mService;
    private boolean mBound = false;
    private boolean mUserSeeking = false;

    private TextView tvTitle, tvStatus, tvPos, tvDur;
    private SeekBar seekBar;
    private ImageButton btnPlay;
    private ListView listSongs;
    private BaseAdapter adapter;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = ((MusicService.LocalBinder) service).getService();
            mBound = true;
            refreshAll();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mBound = false;
        }
    };

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(MusicService.EXTRA_STATE);
            String msg = intent.getStringExtra(MusicService.EXTRA_MSG);
            if (state == null) return;
            switch (state) {
                case "init":
                    tvStatus.setText(getString(R.string.initializing)
                            + (msg != null ? " " + msg : ""));
                    break;
                case "loading":
                    tvStatus.setText("正在载入《" + (msg != null ? msg : "") + "》…");
                    break;
                case "playing":
                    tvStatus.setText(R.string.now_playing);
                    break;
                case "paused":
                    tvStatus.setText(R.string.paused);
                    break;
                case "ready":
                    tvStatus.setText(R.string.now_playing);
                    break;
                case "error":
                    if (msg != null) {
                        tvStatus.setText(msg);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                    break;
                default:
                    break;
            }
            refreshAll();
        }
    };

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
        setContentView(R.layout.activity_main);

        tvTitle = findViewById(R.id.tvTitle);
        tvStatus = findViewById(R.id.tvStatus);
        tvPos = findViewById(R.id.tvPos);
        tvDur = findViewById(R.id.tvDur);
        seekBar = findViewById(R.id.seekBar);
        btnPlay = findViewById(R.id.btnPlay);
        listSongs = findViewById(R.id.listSongs);

        adapter = new SongsAdapter();
        listSongs.setAdapter(adapter);
        listSongs.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (mBound && mService.isInitialized()) {
                    Intent i = new Intent(MainActivity.this, MusicService.class)
                            .setAction(MusicService.ACTION_PLAY_AT)
                            .putExtra(MusicService.EXTRA_INDEX_REQ, position);
                    startService(i);
                }
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
                if (mBound) mService.seekTo(seekBar.getProgress());
            }
        });

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mBound) mService.playPause();
            }
        });
        findViewById(R.id.btnNext).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAction(MusicService.ACTION_NEXT);
            }
        });
        findViewById(R.id.btnPrev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAction(MusicService.ACTION_PREV);
            }
        });
        findViewById(R.id.btnRepeat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mBound) return;
                mService.setRepeatOne(!mService.isRepeatOne());
                refreshModes();
            }
        });
        findViewById(R.id.btnShuffle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mBound) return;
                mService.setShuffle(!mService.isShuffle());
                refreshModes();
            }
        });
        findViewById(R.id.btnExit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAction(MusicService.ACTION_EXIT);
                finishAndRemoveTask();
            }
        });

        // 启动服务并触发“打开即自动播放”
        startService(new Intent(this, MusicService.class).setAction(MusicService.ACTION_ENSURE));
    }

    private void startAction(String action) {
        if (mBound && mService != null) {
            if (MusicService.ACTION_NEXT.equals(action)) mService.next();
            else if (MusicService.ACTION_PREV.equals(action)) mService.prev();
        } else {
            startService(new Intent(this, MusicService.class).setAction(action));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, MusicService.class), mConn, Context.BIND_AUTO_CREATE);
        registerReceiver(mStateReceiver,
                new IntentFilter(MusicService.ACTION_STATE), null, null);
        mHandler.post(mTicker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mTicker);
        try {
            unregisterReceiver(mStateReceiver);
        } catch (Exception ignored) {
        }
        if (mBound) {
            try {
                unbindService(mConn);
            } catch (Exception ignored) {
            }
            mBound = false;
            mService = null;
        }
    }

    private void refreshAll() {
        if (!mBound || mService == null) return;
        int idx = mService.getCurrentIndex();
        if (idx >= 0 && idx < MusicService.TRACK_NAMES.length) {
            tvTitle.setText(MusicService.TRACK_NAMES[idx]);
        } else {
            tvTitle.setText(R.string.app_name);
        }
        btnPlay.setImageResource(mService.isPlaying()
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        seekBar.setEnabled(mService.isInitialized());
        adapter.notifyDataSetChanged();
        refreshModes();
        updateProgress();
    }

    private void refreshModes() {
        if (!mBound || mService == null) return;
        TextView btnRepeat = findViewById(R.id.btnRepeat);
        TextView btnShuffle = findViewById(R.id.btnShuffle);
        btnRepeat.setText(mService.isRepeatOne() ? "单曲循环" : "列表循环");
        btnRepeat.setTextColor(0xFFE6B342);
        btnShuffle.setText(mService.isShuffle() ? "随机：开" : "随机：关");
    }

    private void updateProgress() {
        if (!mBound || mService == null || mUserSeeking) return;
        int dur = mService.getDuration();
        int pos = mService.getPosition();
        seekBar.setMax(dur > 0 ? dur : 1);
        seekBar.setProgress(pos);
        tvPos.setText(fmt(pos));
        tvDur.setText(fmt(dur));
        btnPlay.setImageResource(mService.isPlaying()
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    private static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    /** 曲库列表适配器：当前曲目高亮 */
    private class SongsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return MusicService.TRACK_NAMES.length;
        }

        @Override
        public Object getItem(int position) {
            return MusicService.TRACK_NAMES[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                tv.setTextSize(17f);
                tv.setPadding(24, 30, 24, 30);
            }
            int cur = mBound && mService != null ? mService.getCurrentIndex() : -1;
            boolean isCur = position == cur;
            String name = MusicService.TRACK_NAMES[position];
            tv.setText((isCur ? "♪  " : (position + 1) + ".  ") + name);
            tv.setTextColor(isCur ? 0xFFE6B342 : 0xFFFFFFFF);
            tv.setTypeface(null, isCur
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            return tv;
        }
    }
}
