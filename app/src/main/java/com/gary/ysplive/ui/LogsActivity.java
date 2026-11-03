package com.gary.ysplive.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.gary.ysplive.R;
import com.gary.ysplive.core.spi.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行日志页。
 * <p>绑定 {@link Log#recent(int)} 与 {@link Log.Listener}，自动滚到底部，
 * 支持「复制全部」与「清空」。数据源是 core 的全局环形缓冲，因此 Activity 重建后依然完整。
 */
public final class LogsActivity extends Activity implements Log.Listener {

    private static final String TAG = "logs";
    private static final int LIMIT = 400;
    /** 日志到达时的合并刷新窗口，避免高频日志造成列表卡顿。 */
    private static final long FLUSH_DELAY_MS = 250L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> lines = new ArrayList<>();

    private ListView list;
    private LogAdapter adapter;
    private TextView countText;
    private TextView emptyText;

    private volatile boolean dirty;
    private boolean flushScheduled;

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
            reload();
        }
    };

    public static void start(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.startActivity(new Intent(ctx, LogsActivity.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "打开日志页失败：" + e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);

        list = (ListView) findViewById(R.id.log_list);
        countText = (TextView) findViewById(R.id.log_count);
        emptyText = (TextView) findViewById(R.id.log_empty);

        adapter = new LogAdapter();
        list.setAdapter(adapter);
        // 让列表可以接 D-pad 焦点滚动
        list.setItemsCanFocus(false);

        findViewById(R.id.btn_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAll();
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.clear();
                reload();
            }
        });
        findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.addListener(this);
        reload();
    }

    @Override
    protected void onPause() {
        Log.removeListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Log.removeListener(this);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------- 数据 ----------------

    private void reload() {
        List<Log.Entry> entries;
        try {
            entries = Log.recent(LIMIT);
        } catch (RuntimeException e) {
            entries = new ArrayList<>();
        }
        lines.clear();
        for (Log.Entry entry : entries) {
            lines.add(entry.format());
        }
        adapter.notifyDataSetChanged();

        boolean empty = lines.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        countText.setText(lines.isEmpty() ? "" : lines.size() + " 条");

        if (!empty) {
            list.setSelection(lines.size() - 1);
        }
        dirty = false;
    }

    private void scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        main.postDelayed(flushTask, FLUSH_DELAY_MS);
    }

    @Override
    public void onLog(Log.Entry entry) {
        dirty = true;
        // 回调可能在任意线程，统一切主线程合并刷新。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scheduleFlush();
        } else {
            main.post(new Runnable() {
                @Override
                public void run() {
                    scheduleFlush();
                }
            });
        }
    }

    private void copyAll() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        if (sb.length() == 0) {
            Ui.toast(this, "暂无日志可复制");
            return;
        }
        Ui.copyToClipboard(this, "ysp-logs", sb.toString());
    }

    // ---------------- 适配器 ----------------

    private final class LogAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return lines.size();
        }

        @Override
        public String getItem(int position) {
            return position >= 0 && position < lines.size() ? lines.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view;
            if (convertView instanceof TextView) {
                view = (TextView) convertView;
            } else {
                view = (TextView) getLayoutInflater().inflate(R.layout.item_log, parent, false);
            }
            String line = getItem(position);
            view.setText(line == null ? "" : line);
            if (line != null) {
                if (line.contains("[error]") || line.contains("失败") || line.contains("错误")) {
                    view.setTextColor(LogsActivity.this.getResources().getColor(R.color.error));
                } else if (line.contains("警告") || line.contains("超时")) {
                    view.setTextColor(LogsActivity.this.getResources().getColor(R.color.warning));
                } else {
                    view.setTextColor(LogsActivity.this.getResources().getColor(R.color.text_primary));
                }
            }
            return view;
        }
    }
}
