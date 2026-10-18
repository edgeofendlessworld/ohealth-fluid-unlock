package io.github.endlessworld.ohealthfluid;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * 把第三方应用通知桥接成流体云卡片。
 *
 * <p>不自行注册新的 {@link NotificationListenerService}（那需要用户在系统设置里重新授权），
 * 而是 hook OPPO 健康自己的监听服务 —— 它本来就在接收全系统通知：
 *
 * <pre>
 * com.heytap.health.watch.commonnotification.HeytapNotificationListenerService
 *   extends NotificationListenerService
 * </pre>
 *
 * <p>在 {@code onNotificationPosted} 处取到 {@link StatusBarNotification}，
 * 命中内置白名单的包就转成卡片，通过 {@link FluidInjector} 走外部注入通道。
 */
final class NotificationBridge {

    private static final String TAG = "OHealthFluidUnlock";

    private static final String NLS =
            "com.heytap.health.watch.commonnotification.HeytapNotificationListenerService";

    /** 最近处理过的通知，避免同一张卡片在短时间内被反复注入。 */
    private static final Set<String> RECENT = new HashSet<>();

    private static final long DEDUP_WINDOW_MS = 10_000L;
    private static final java.util.Map<String, Long> SEEN_AT = new java.util.HashMap<>();

    /**
     * 是否把第三方通知伪造成卡片。
     *
     * <p>对照实验做过一轮（关掉伪造、只留闸门），原生链路对百度地图这类通知<b>完全不产卡</b>：
     * {@code FluidManager.sendCardData} / {@code CloudPushManager.cloudPush} 一次都没命中，
     * 只有我们自己的注入进得来。所以卡片内容只能由我们来喂，这里重新打开。
     *
     * <p>随之补上的载荷（见 {@code FluidInjector}）：原生卡片必带的 {@code KEY_IMAGES}
     * （D1 大图 + A1 小图，来自数据源 App 图标）和 {@code KEY_TYPES} 模板键。
     */
    private static final boolean BRIDGE_ENABLED = true;

    private NotificationBridge() {
    }

    static void install(ClassLoader cl, io.github.libxposed.api.XposedInterface xp) {
        if (!BRIDGE_ENABLED) {
            Log.i(TAG, "[BRIDGE] disabled -> 观察原生链路（白名单应用通知由 App 自己处理）");
            return;
        }
        final Class<?> rankingMapCls = NotificationListenerService.RankingMap.class;

        // 主路径：hook OPPO 健康自己的监听服务实现
        if (tryHook(cl, xp, NLS, rankingMapCls, false)) {
            return;
        }
        // 兜底：hook 系统基类回调（仅处理 com.heytap.health 进程内的实例）
        tryHook(cl, xp, NotificationListenerService.class.getName(), rankingMapCls, true);
    }

    /**
     * @param restricted 为 true 时只处理 com.heytap.health 包内的实例，避免影响其它 App
     */
    private static boolean tryHook(ClassLoader cl,
                                   io.github.libxposed.api.XposedInterface xp,
                                   String className,
                                   Class<?> rankingMapCls,
                                   boolean restricted) {
        try {
            final Class<?> cls = restricted
                    ? NotificationListenerService.class
                    : Class.forName(className, false, cl);

            final java.lang.reflect.Method onPosted = cls.getDeclaredMethod(
                    "onNotificationPosted", StatusBarNotification.class, rankingMapCls);
            onPosted.setAccessible(true);

            xp.hook(onPosted).intercept(chain -> {
                final Object result;
                try {
                    result = chain.proceed();
                } catch (Throwable t) {
                    // 原实现抛错也不能影响我们处理
                    handleQuietly(cl, chain, restricted);
                    throw t;
                }
                handleQuietly(cl, chain, restricted);
                return result;
            });
            Log.i(TAG, "hooked bridge   : " + className
                    + "#onNotificationPosted(StatusBarNotification, RankingMap)");
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "SKIP bridge on " + className + ": " + t.getClass().getSimpleName());
            return false;
        }
    }

    private static void handleQuietly(ClassLoader cl, io.github.libxposed.api.XposedInterface.Chain chain,
                                      boolean restricted) {
        try {
            if (restricted) {
                final Object self = chain.getThisObject();
                if (self == null
                        || !self.getClass().getName().startsWith("com.heytap.health")) {
                    return;
                }
            }
            final Object sbnArg = chain.getArg(0);
            if (sbnArg instanceof StatusBarNotification) {
                handle(cl, (StatusBarNotification) sbnArg);
            }
        } catch (Throwable t) {
            Log.e(TAG, "[BRIDGE] handle failed: " + t, t);
        }
    }

    private static void handle(ClassLoader cl, StatusBarNotification sbn) {
        final String pkg = sbn.getPackageName();
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        // 跳过自己，避免递归
        if ("com.heytap.health".equals(pkg)) {
            return;
        }
        // 只处理带内容的通知
        final Notification n = sbn.getNotification();
        if (n == null) {
            return;
        }

        // 白名单校验：不在 App 内置白名单里的包，注入也会被 sendCardData 拒掉
        if (!FluidInjector.isWhitelisted(cl, pkg)) {
            return;
        }

        if (isDuplicate(sbn)) {
            return;
        }

        final String[] text = FluidInjector.extractText(sbn);
        final String title = text[0].isEmpty() ? pkg : text[0];
        final String body = text[1];

        Log.i(TAG, "[BRIDGE] whitelisted notification: pkg=" + pkg
                + ", title=" + title + ", text=" + body);

        FluidInjector.inject(cl, pkg, title, body, 0);
    }

    private static boolean isDuplicate(StatusBarNotification sbn) {
        final String key = sbn.getPackageName() + "|" + sbn.getId() + "|" + sbn.getTag();
        final long now = System.currentTimeMillis();
        synchronized (SEEN_AT) {
            final Long last = SEEN_AT.get(key);
            if (last != null && now - last < DEDUP_WINDOW_MS) {
                return true;
            }
            SEEN_AT.put(key, now);
            if (SEEN_AT.size() > 256) {
                SEEN_AT.clear();
            }
        }
        return false;
    }
}
