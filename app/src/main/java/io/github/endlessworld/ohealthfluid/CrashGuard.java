package io.github.endlessworld.ohealthfluid;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 兜住因 ColorOS 组件缺失导致的跳转崩溃。
 *
 * <p>流体云列表项 / 总开关点击后会跳转到 ColorOS 的通知管理应用：
 *
 * <pre>
 * // FluidFragment.jumpBrand(BrandItem)
 * Intent("com.oplus.notificationmanager.action.activity.FLUID_SEED_SERVICE_SETTING_APP_DETAIL")
 *     .setPackage("com.oplus.notificationmanager")   // ← 非 ColorOS 上不存在
 *
 * // FluidFragment.jumpMain()
 * Intent("oplus.fluid.seeding.settings.main")
 *     .setPackage("com.oplus.notificationmanager")   // ← 同上
 * </pre>
 *
 * <p>{@code startActivity} 会抛 {@link ActivityNotFoundException} 且无人捕获，
 * 直接导致 OPPO 健康闪退。这里把该调用拦下来，给一个 Toast 提示后正常返回，
 * 使页面保持可用（列表本身是有效的，只是"进入系统设置"这一跳无法完成）。
 */
final class CrashGuard {

    private static final String TAG = "OHealthFluidUnlock";
    private static final String NOTIFY_MGR_PACKAGE = "com.oplus.notificationmanager";

    private static volatile boolean toastShown;

    private CrashGuard() {
    }

    static void install(ClassLoader cl, XposedInterface xp) {
        hookStartActivity(xp);
    }

    /**
     * Hook {@code Activity.startActivity}。只拦截指向 ColorOS 通知管理应用的跳转，
     * 其余原样放行。
     */
    private static void hookStartActivity(XposedInterface xp) {
        try {
            final Method startActivity = Activity.class.getDeclaredMethod(
                    "startActivity", Intent.class);
            startActivity.setAccessible(true);

            xp.hook(startActivity).intercept(chain -> {
                final Object intentArg = chain.getArg(0);
                if (!(intentArg instanceof Intent)) {
                    return chain.proceed();
                }
                final Intent intent = (Intent) intentArg;

                if (!isNotifyMgrIntent(intent)) {
                    return chain.proceed();
                }

                // 目标包不存在就直接静默处理，避免 ActivityNotFoundException 冒泡
                final Object self = chain.getThisObject();
                final Context ctx = self instanceof Activity
                        ? ((Activity) self).getApplicationContext()
                        : applicationContext();

                Log.i(TAG, "[GUARD] blocked jump to absent component: " + intent.getAction()
                        + " (" + intent.getPackage() + ")");

                notifyUnavailable(ctx, intent);
                return null;    // 跳过原始 startActivity 调用
            });
            Log.i(TAG, "hooked guard    : Activity#startActivity");
        } catch (Throwable t) {
            Log.i(TAG, "SKIP Activity#startActivity: " + t.getClass().getSimpleName());
        }
    }

    /** 仅当目标包是 ColorOS 通知管理应用、或 action 属于流体云设置时才拦截。 */
    private static boolean isNotifyMgrIntent(Intent intent) {
        if (NOTIFY_MGR_PACKAGE.equals(intent.getPackage())) {
            return true;
        }
        final String action = intent.getAction();
        return action != null && action.startsWith("oplus.fluid.");
    }

    /** 提示只在同一次会话里弹一次，避免用户反复点击时刷屏。 */
    private static void notifyUnavailable(Context ctx, Intent intent) {
        final boolean isMain = intent.getAction() != null
                && intent.getAction().startsWith("oplus.fluid.");
        final String msg = isMain
                ? "该页面由 ColorOS 通知管理提供，当前系统不可用"
                : "应用详情页由 ColorOS 通知管理提供，当前系统不可用";

        Log.i(TAG, "[GUARD] " + msg);
        if (ctx == null || toastShown) {
            return;
        }
        toastShown = true;
        try {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "toast failed: " + t.getClass().getSimpleName());
        }
    }

    private static Context applicationContext() {
        try {
            final Class<?> at = Class.forName("android.app.ActivityThread");
            final Method current = at.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            final Object app = current.invoke(null);
            if (app instanceof Application) {
                return (Application) app;
            }
        } catch (Throwable ignored) {
            // 兜底链继续
        }
        try {
            final Class<?> holder = Class.forName(
                    "com.heytap.health.base.base.GlobalApplicationHolder");
            final Method get = holder.getDeclaredMethod("getAppContext");
            get.setAccessible(true);
            final Object c = get.invoke(null);
            if (c instanceof Context) {
                return (Context) c;
            }
        } catch (Throwable ignored) {
            // 两条路都失败
        }
        return null;
    }

    /** 供 {@link ContextWrapper} 场景使用的占位，保持向上一层解包的兼容性。 */
    @SuppressWarnings("unused")
    private static Context unwrap(Context ctx) {
        Context cur = ctx;
        while (cur instanceof ContextWrapper && !(cur instanceof Activity)) {
            final Context base = ((ContextWrapper) cur).getBaseContext();
            if (base == null || base == cur) {
                break;
            }
            cur = base;
        }
        return cur;
    }
}
