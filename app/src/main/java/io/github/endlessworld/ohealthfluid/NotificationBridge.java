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

    /** 最近一次注入的内容签名：{@code pkg|id|tag → title\u0001text}，用于丢掉完全相同的重复回调。 */
    private static final java.util.Map<String, String> LAST_SIG = new java.util.HashMap<>();

    /** 我们真正注入过的卡片 key（{@code pkg|id|tag}），只对这些卡做撤销，避免误删原生卡。 */
    private static final Set<String> INJECTED = new HashSet<>();

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
        final boolean main = tryHook(cl, xp, NLS, rankingMapCls, false);
        if (main) {
            // 撤销回调不一定被子类重写：基类实现也挂一份（restricted 只处理健康进程内的实例）
            hookRemoved(cl, xp, NotificationListenerService.class, rankingMapCls, true);
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
            hookRemoved(cl, xp, cls, rankingMapCls, restricted);
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "SKIP bridge on " + className + ": " + t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 通知消失时把卡片一并撤掉。
     *
     * <p>原生链路是在 {@code onNotificationRemoved} 里推 {@code onNotificationFluidRemoved}；
     * 我们只 hook 了 posted，所以以前源通知没了、手表上的卡却一直挂着。
     * 3 参版本（API 26+ 的 {@code reason}）存在时也一起挂上。
     */
    private static void hookRemoved(ClassLoader cl, io.github.libxposed.api.XposedInterface xp,
                                    Class<?> cls, Class<?> rankingMapCls, boolean restricted) {
        String hooked = null;
        for (String desc : new String[]{"(sbn, map)", "(sbn, map, reason)"}) {
            try {
                final java.lang.reflect.Method m = desc.equals("(sbn, map)")
                        ? cls.getDeclaredMethod("onNotificationRemoved",
                                StatusBarNotification.class, rankingMapCls)
                        : cls.getDeclaredMethod("onNotificationRemoved",
                                StatusBarNotification.class, rankingMapCls, int.class);
                m.setAccessible(true);
                xp.hook(m).intercept(chain -> {
                    final Object result;
                    try {
                        result = chain.proceed();
                    } catch (Throwable t) {
                        handleRemovedQuietly(cl, chain, restricted);
                        throw t;
                    }
                    handleRemovedQuietly(cl, chain, restricted);
                    return result;
                });
                hooked = hooked == null ? desc : hooked + " + " + desc;
            } catch (Throwable t) {
                Log.i(TAG, "SKIP onNotificationRemoved" + desc + " on " + cls.getSimpleName()
                        + ": " + t.getClass().getSimpleName());
            }
        }
        if (hooked != null) {
            Log.i(TAG, "hooked bridge   : " + cls.getName() + "#onNotificationRemoved" + hooked);
        }
    }

    private static void handleRemovedQuietly(ClassLoader cl,
                                             io.github.libxposed.api.XposedInterface.Chain chain,
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
                handleRemoved(cl, (StatusBarNotification) sbnArg);
            }
        } catch (Throwable t) {
            Log.e(TAG, "[BRIDGE] handleRemoved failed: " + t, t);
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

        final String[] text = FluidInjector.extractText(sbn);
        final String title = text[0].isEmpty() ? pkg : text[0];
        final String body = text[1];

        if (isDuplicate(sbn, title, body)) {
            return;
        }

        Log.i(TAG, "[BRIDGE] whitelisted notification: pkg=" + pkg + ", id=" + sbn.getId()
                + ", title=" + title + ", text=" + body);

        // 卡片 id 沿用源通知 id：更新才是替换同一张卡，撤销也才找得到它
        if (FluidInjector.inject(cl, pkg, title, body, 0, sbn.getId())) {
            synchronized (INJECTED) {
                INJECTED.add(keyOf(sbn));
            }
        }
    }

    /** 源通知被移除 → 手表上的卡也要撤掉，否则会留一张关不掉的僵尸卡。 */
    private static void handleRemoved(ClassLoader cl, StatusBarNotification sbn) {
        final String pkg = sbn.getPackageName();
        if (pkg == null || pkg.isEmpty() || "com.heytap.health".equals(pkg)) {
            return;
        }
        final String key = keyOf(sbn);
        final boolean ours;
        synchronized (INJECTED) {
            ours = INJECTED.remove(key);
        }
        synchronized (LAST_SIG) {
            LAST_SIG.remove(key);
        }
        if (!ours) {
            return;
        }
        Log.i(TAG, "[BRIDGE] notification removed: pkg=" + pkg + ", id=" + sbn.getId());
        FluidInjector.remove(cl, pkg, sbn.getId());
    }

    private static String keyOf(StatusBarNotification sbn) {
        return sbn.getPackageName() + "|" + sbn.getId() + "|" + sbn.getTag();
    }

    /**
     * 是否与上一次注入的内容完全一样。
     *
     * <p>导航类通知几秒钟就更新一次正文；以前的实现用「10 秒时间窗」去重，
     * 把真正的更新也一起丢了 —— 手表上表现为「内容永远不变」。
     * 现在只按内容签名去重：内容变了就放行，内容没变则不必重复推。
     */
    private static boolean isDuplicate(StatusBarNotification sbn, String title, String body) {
        final String key = keyOf(sbn);
        final String sig = title + '\u0001' + body;
        synchronized (LAST_SIG) {
            if (sig.equals(LAST_SIG.get(key))) {
                return true;
            }
            LAST_SIG.put(key, sig);
            if (LAST_SIG.size() > 256) {
                LAST_SIG.clear();
            }
        }
        return false;
    }
}
