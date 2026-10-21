package com.tvproxy.ui;

import com.tvproxy.R;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tvproxy.adb.AdbClient;
import com.tvproxy.core.ProxyManager;

/**
 * TV 系统代理切换器 主界面。
 * 布局: 标题 + 状态 + 3 个按钮 (开启系统代理 / 关闭系统代理 / 重启电视)
 */
public class ProxyActivity extends Activity {

    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.proxy_main);

        statusText = (TextView) findViewById(R.id.status_text);
        logText = (TextView) findViewById(R.id.log_text);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);

        Button btnOn = (Button) findViewById(R.id.btn_proxy_on);
        Button btnOff = (Button) findViewById(R.id.btn_proxy_off);
        Button btnReboot = (Button) findViewById(R.id.btn_reboot);

        // 启动时刷新状态 (后台线程避免 NetworkOnMainThreadException)
        refreshStatus();

        // 首焦点给第一个按钮 (遥控器默认焦点在顶部)
        btnOn.requestFocus();

        btnOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAction("开启系统代理 (127.0.0.1:7890)", new Runnable() {
                    @Override public void run() {
                        try { ProxyManager.enableSystemProxy(); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }
                });
            }
        });

        btnOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAction("关闭系统代理", new Runnable() {
                    @Override public void run() {
                        try { ProxyManager.disableSystemProxy(); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }
                });
            }
        });

        btnReboot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReboot();
            }
        });
    }

    private void runAction(final String label, final Runnable action) {
        appendLog("▶ " + label + " ...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    action.run();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            appendLog("✅ 完成");
                            refreshStatus();
                        }
                    });
                } catch (final Exception e) {
                    Log.e("TvProxySet", "runAction 失败: " + label, e);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            appendLog("❌ 失败: " + e);
                        }
                    });
                }
            }
        }).start();
    }

    private void confirmReboot() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("重启电视");
        b.setMessage("确认重启电视吗?\n(代理配置将在重启后生效/清除)");
        b.setPositiveButton("重启", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                appendLog("▶ 重启电视 ...");
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            ProxyManager.reboot();
                        } catch (final Exception e) {
                            Log.e("TvProxySet", "reboot 失败", e);
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    appendLog("❌ " + e);
                                }
                            });
                        }
                    }
                }).start();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void refreshStatus() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String enabled = ProxyManager.isSystemProxyEnabled();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            statusText.setText("系统代理: " + ("true".equals(enabled.trim()) ? "开启 ●" : "关闭 ○"));
                        }
                    });
                } catch (final Exception e) {
                    Log.e("TvProxySet", "refreshStatus 失败", e);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            statusText.setText("状态读取失败: " + e);
                        }
                    });
                }
            }
        }).start();
    }

    private void appendLog(String line) {
        if (logText != null) {
            logText.setText(logText.getText() + "\n" + line);
            // 自动滚动到底部显示最新日志
            if (logScroll != null) {
                logScroll.post(new Runnable() {
                    @Override public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        }
    }
}