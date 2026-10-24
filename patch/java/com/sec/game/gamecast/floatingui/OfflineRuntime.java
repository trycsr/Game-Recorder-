package com.sec.game.gamecast.floatingui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.widget.Toast;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replacements for things that only worked on old Samsung firmware:
 * - detects the app in the foreground with Usage Access instead of the hidden
 *   IProcessObserver API (which needs a system-only permission);
 * - asks for the overlay and Usage Access permissions;
 * - saves crashes and shows them on the next launch.
 */
public final class OfflineRuntime {
    private static final String CRASH_FILE = "offline_last_crash.txt";
    private static final long POLL_MS = 1000;
    // Fake pids handed to the app, one per package. Far above real pids.
    private static final int FAKE_PID_BASE = 0x40000000;

    private static final Map<String, Integer> sPidByPkg = new HashMap<String, Integer>();
    private static final Map<Integer, String> sPkgByPid = new HashMap<Integer, String>();

    private static Handler sHandler;
    private static Runnable sPoller;
    private static ProcessTopMonitorCallback sCallback;
    private static Context sContext;
    private static String sCurrentPkg;
    private static long sLastQuery;
    private static boolean sAskedThisProcess;

    private OfflineRuntime() {
    }

    // ---------------------------------------------------------------- crash log

    public static void installCrashLogger(final Context ctx) {
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
                    pw.println(Build.MANUFACTURER + " " + Build.MODEL);
                    pw.println("Thread: " + t.getName());
                    e.printStackTrace(pw);
                    pw.flush();
                    FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), CRASH_FILE));
                    out.write(sw.toString().getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) {
                    // nothing else we can do
                }
                if (previous != null) {
                    previous.uncaughtException(t, e);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    /**
     * Shows the last crash, if any. Returns true when a dialog is shown; the
     * activity's private method {@code continueMethod} runs when it is closed.
     */
    public static boolean showLastCrash(final Activity activity, final String continueMethod) {
        final String text;
        try {
            File f = new File(activity.getFilesDir(), CRASH_FILE);
            if (!f.exists()) {
                return false;
            }
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            in.close();
            f.delete();
            text = new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return false;
        }
        final boolean pt = isPortuguese();
        final Runnable cont = new Runnable() {
            @Override
            public void run() {
                if (continueMethod == null) {
                    // Restart this screen; the crash file is gone now.
                    try {
                        activity.startActivity(activity.getIntent());
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    activity.finish();
                    return;
                }
                try {
                    Method m = activity.getClass().getDeclaredMethod(continueMethod);
                    m.setAccessible(true);
                    m.invoke(activity);
                } catch (Throwable t) {
                    activity.finish();
                }
            }
        };
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(pt ? "O app fechou na última vez" : "The app crashed last time")
                    .setMessage((pt ? "Copie este texto e envie para quem modificou o app:\n\n"
                            : "Copy this text and send it to whoever patched the app:\n\n") + text)
                    .setCancelable(false)
                    .setPositiveButton(pt ? "Copiar e continuar" : "Copy and continue", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("crash", text));
                                Toast.makeText(activity, pt ? "Copiado" : "Copied", Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {
                                // ignore
                            }
                            cont.run();
                        }
                    })
                    .setNegativeButton(pt ? "Continuar" : "Continue", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            cont.run();
                        }
                    })
                    .show();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------- foreground detection

    public static synchronized void startWatcher(Context ctx, ProcessTopMonitorCallback callback) {
        sContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        sCallback = callback;
        if (Build.VERSION.SDK_INT < 22) {
            return;
        }
        if (sHandler == null) {
            sHandler = new Handler(Looper.getMainLooper());
        }
        if (sPoller != null) {
            return;
        }
        sCurrentPkg = null;
        sLastQuery = System.currentTimeMillis() - 60 * 1000;
        sPoller = new Runnable() {
            @Override
            public void run() {
                try {
                    poll();
                } catch (Throwable ignored) {
                    // keep polling
                }
                if (sPoller == this) {
                    sHandler.postDelayed(this, POLL_MS);
                }
            }
        };
        sHandler.post(sPoller);
    }

    public static synchronized void stopWatcher() {
        if (sHandler != null && sPoller != null) {
            sHandler.removeCallbacks(sPoller);
        }
        sPoller = null;
        sCallback = null;
        sCurrentPkg = null;
    }

    private static void poll() {
        if (sContext == null || sCallback == null) {
            return;
        }
        UsageStatsManager usm = (UsageStatsManager) sContext.getSystemService("usagestats");
        if (usm == null) {
            return;
        }
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(sLastQuery, now);
        sLastQuery = now - 2000; // small overlap so no event is lost
        if (events == null) {
            return;
        }
        String latest = null;
        long latestTime = 0;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            // 1 = MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
            if (e.getEventType() == 1 && e.getTimeStamp() >= latestTime) {
                latestTime = e.getTimeStamp();
                latest = e.getPackageName();
            }
        }
        if (latest == null || isIgnored(latest) || latest.equals(sCurrentPkg)) {
            return;
        }
        String old = sCurrentPkg;
        sCurrentPkg = latest;
        if (old != null) {
            sCallback.onForegroundActivitiesChangedOverL(pidFor(old), uidFor(old), false, old);
        }
        sCallback.onForegroundActivitiesChangedOverL(pidFor(latest), uidFor(latest), true, latest);
    }

    /** Apps that only show something on top of the game (permission dialogs, our own screens). */
    private static boolean isIgnored(String pkg) {
        return pkg.equals(sContext.getPackageName())
                || pkg.equals("android")
                || pkg.equals("com.android.systemui")
                || pkg.equals("com.android.permissioncontroller")
                || pkg.equals("com.google.android.permissioncontroller")
                || pkg.equals("com.android.packageinstaller")
                || pkg.equals("com.google.android.packageinstaller");
    }

    private static synchronized int pidFor(String pkg) {
        Integer pid = sPidByPkg.get(pkg);
        if (pid == null) {
            pid = FAKE_PID_BASE + sPidByPkg.size() + 1;
            sPidByPkg.put(pkg, pid);
            sPkgByPid.put(pid, pkg);
        }
        return pid;
    }

    private static int uidFor(String pkg) {
        try {
            ApplicationInfo ai = sContext.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Same as the app's original pid lookup, but knows the fake pids too. */
    public static String packageForPid(Context ctx, int pid) {
        synchronized (OfflineRuntime.class) {
            String pkg = sPkgByPid.get(pid);
            if (pkg != null) {
                return pkg;
            }
        }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> list = am != null ? am.getRunningAppProcesses() : null;
            if (list != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo info : list) {
                    if (info.pid == pid) {
                        return info.processName;
                    }
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return "";
    }

    // ------------------------------------------------------- screen capture

    private static MediaProjection sProjection;
    private static int sResultCode;
    private static Intent sResultData;
    private static long sLastConsentRequest;

    /**
     * Replaces the app's MediaProjectionGetter, which needed a system-only
     * permission. Returns the granted projection, or null after asking the
     * user (Android's own dialog); the user then taps record again.
     */
    public static synchronized MediaProjection getMediaProjection(Context ctx, boolean unused) {
        if (sProjection != null) {
            return sProjection;
        }
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        if (sResultData != null) {
            try {
                MediaProjectionManager mpm = (MediaProjectionManager) app.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                MediaProjection p = mpm.getMediaProjection(sResultCode, (Intent) sResultData.clone());
                if (p != null) {
                    keep(p);
                    return p;
                }
            } catch (Throwable t) {
                // grant can no longer be reused; ask again below
            }
            sResultData = null;
        }
        long now = System.currentTimeMillis();
        if (now - sLastConsentRequest > 3000) {
            sLastConsentRequest = now;
            try {
                Intent i = new Intent(app, OfflineProjectionActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                app.startActivity(i);
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return null;
    }

    static synchronized void onProjectionResult(Context ctx, int resultCode, Intent data) {
        boolean pt = isPortuguese();
        if (resultCode == Activity.RESULT_OK && data != null) {
            sResultCode = resultCode;
            sResultData = (Intent) data.clone();
            try {
                MediaProjectionManager mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                MediaProjection p = mpm.getMediaProjection(resultCode, data);
                if (p != null) {
                    keep(p);
                }
            } catch (Throwable ignored) {
                // created again on the next tap
            }
            toast(ctx, pt ? "Pronto! Toque no botão de gravar de novo." : "Done! Tap the record button again.");
        } else {
            toast(ctx, pt ? "Sem essa permissão não dá para gravar a tela." : "Screen recording needs this permission.");
        }
    }

    private static void keep(final MediaProjection p) {
        sProjection = p;
        try {
            p.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    synchronized (OfflineRuntime.class) {
                        if (sProjection == p) {
                            sProjection = null;
                            // A stopped grant can't be trusted again: ask next time
                            // (Android 8/9 has "don't show again" in that dialog).
                            sResultData = null;
                        }
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void toast(Context ctx, String text) {
        try {
            Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------ permissions

    /** Asks, one at a time, for the two special permissions recording needs. */
    public static void checkSpecialPermissions(final Activity activity) {
        if (Build.VERSION.SDK_INT < 23 || sAskedThisProcess) {
            return;
        }
        try {
            final boolean pt = isPortuguese();
            String title;
            String message;
            final Intent intent;
            if (!Settings.canDrawOverlays(activity)) {
                title = pt ? "Permitir sobreposição" : "Allow display over other apps";
                message = pt
                        ? "Para mostrar o botão de gravar por cima dos jogos, ative \"Sobrepor a outros apps\" para o Game Recorder+."
                        : "To show the record button over your games, turn on \"Display over other apps\" for Game Recorder+.";
                intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName()));
            } else if (!hasUsageAccess(activity)) {
                title = pt ? "Permitir acesso de uso" : "Allow usage access";
                message = pt
                        ? "Para saber quando um jogo foi aberto, ative o \"Acesso aos dados de uso\" para o Game Recorder+."
                        : "To know when a game is opened, turn on \"Usage access\" for Game Recorder+.";
                intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            } else {
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(pt ? "Abrir configurações" : "Open settings", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                activity.startActivity(intent);
                            } catch (Throwable t) {
                                try {
                                    activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                                } catch (Throwable ignored) {
                                    // ignore
                                }
                            }
                        }
                    })
                    .setNegativeButton(pt ? "Agora não" : "Not now", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            sAskedThisProcess = true;
                        }
                    })
                    .show();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static boolean hasUsageAccess(Context ctx) {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), ctx.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return ctx.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS") == 0;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isPortuguese() {
        return "pt".equals(Locale.getDefault().getLanguage());
    }
}
