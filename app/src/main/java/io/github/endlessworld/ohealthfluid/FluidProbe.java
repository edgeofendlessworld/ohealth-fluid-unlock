package io.github.endlessworld.ohealthfluid;

import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * 只读探针：把流体云卡片从「注入」到「推送到手表」的整条链打上观测点。
 *
 * <p>要回答的核心问题是：数据到底断在哪一环。链路如下：
 *
 * <pre>
 * ExternalFluidCloudInjector.doInject          ← 我注入的入口（已知能到）
 *   └─ HealthNotificationRegisterCenter.onNotificationFluid
 *        └─ NotificationEventManager.onNotificationFluid
 *             └─ ConverterWorker.convertFluidNew      ← 卡片序列化（按手表世代二选一）
 *                  ├─ newPb(hnb, bundle, builder)     ← isNewPbField()==true
 *                  └─ oldPb(bundle, builder)          ← isNewPbField()==false
 *                       └─ CloudPushManager.cloudPush  ← 真正发给手表
 * </pre>
 *
 * <p>判读要点：
 * <ul>
 *   <li>若 {@code convertFluidNew} / {@code newPb} 出现 → 序列化在跑，问题在字段内容</li>
 *   <li>若 {@code CloudPushManager.cloudPush} 从不出现 → 卡在传输层（需要设备密钥协商），
 *       这属于 ColorOS 环境依赖，模块无法绕过</li>
 * </ul>
 */
final class FluidProbe {

    private static final String TAG = "OHealthFluidUnlock";

    private static final String CONVERTER =
            "com.heytap.health.watch.notification.impl.transceiver.convert.ConverterWorker";
    private static final String CLOUD_PUSH =
            "com.heytap.health.watch.notification.impl.cloud.CloudPushManager";
    private static final String EVENT_MANAGER =
            "com.heytap.health.watch.notification.impl.transceiver.NotificationEventManager";
    private static final String REGISTER_CENTER =
            "com.heytap.health.watch.notification.HealthNotificationRegisterCenter";

    private FluidProbe() {
    }

    static void install(ClassLoader cl, XposedInterface xp) {
        // 1) 分发中心（注入后的第一站）
        observe(cl, xp, REGISTER_CENTER, "onNotificationFluid",
                "com.heytap.health.watch.notification.HealthNotificationBean",
                "android.os.Bundle");

        // 2) 事件管理器 → 决定是否推手表
        observe(cl, xp, EVENT_MANAGER, "onNotificationFluid",
                "com.heytap.health.watch.notification.HealthNotificationBean",
                "android.os.Bundle");

        // 3) 卡片序列化入口：能直接看出手表走的是新协议还是旧协议
        observe(cl, xp, CONVERTER, "convertFluidNew",
                "com.heytap.health.watch.notification.HealthNotificationBean",
                "android.os.Bundle",
                "com.heytap.health.watch.notification.ParsedNotificationProto$Builder");

        // 4) 新旧协议各自的字段填充点
        observe(cl, xp, CONVERTER, "newPb",
                "com.heytap.health.watch.notification.HealthNotificationBean",
                "android.os.Bundle",
                "com.heytap.health.watch.notification.SeedingCardStyleProto$Builder");
        observe(cl, xp, CONVERTER, "oldPb",
                "android.os.Bundle",
                "com.heytap.health.watch.notification.SeedingCardStyleProto$Builder");

        // 5) 真正推送到手表（最关键：不出现说明卡在传输层）
        observe(cl, xp, CLOUD_PUSH, "cloudPush",
                "com.heytap.health.watch.notification.HealthNotificationBean");
    }

    /**
     * 挂一个只读观测点：打印全部参数摘要与返回值，不改写任何行为。
     */
    private static void observe(ClassLoader cl, XposedInterface xp,
                                String className, String methodName, String... paramTypes) {
        try {
            final Class<?> cls = Class.forName(className, false, cl);
            final Method m = findMethod(cls, methodName, cl, paramTypes);
            if (m == null) {
                return;
            }
            m.setAccessible(true);

            final String label = simpleName(className) + "#" + methodName;
            xp.hook(m).intercept(chain -> {
                final Object result;
                try {
                    result = chain.proceed();
                } catch (Throwable t) {
                    Log.w(TAG, "[PROBE] " + label + " threw: " + t);
                    throw t;
                }
                final StringBuilder sb = new StringBuilder("[PROBE] ").append(label);
                for (Object a : chain.getArgs()) {
                    sb.append(" | ").append(summarize(a));
                }
                if (!isVoid(m)) {
                    sb.append(" => ").append(summarize(result));
                }
                Log.i(TAG, sb.toString());
                return result;
            });
            Log.i(TAG, "probe installed : " + label);
        } catch (Throwable t) {
            Log.i(TAG, "SKIP probe " + className + "#" + methodName + ": "
                    + t.getClass().getSimpleName());
        }
    }

    /** 精确签名优先；失败则按方法名取第一个重载（容忍不同版本的参数差异）。 */
    private static Method findMethod(Class<?> cls, String name, ClassLoader cl, String... paramTypes) {
        try {
            final Class<?>[] types = new Class<?>[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                types[i] = Class.forName(paramTypes[i], false, cl);
            }
            final Method m = cls.getDeclaredMethod(name, types);
            return m;
        } catch (NoSuchMethodException ignored) {
            // 退化为按名匹配
        } catch (Throwable t) {
            Log.i(TAG, "probe signature error " + name + ": " + t.getClass().getSimpleName());
            return null;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) {
                Log.i(TAG, "probe: " + name + " matched by name (params differ)");
                return m;
            }
        }
        Log.i(TAG, "probe: " + name + " not found on " + cls.getName());
        return null;
    }

    private static boolean isVoid(Method m) {
        return m.getReturnType() == void.class;
    }

    // ------------------------------------------------------------------
    // 摘要打印
    // ------------------------------------------------------------------

    static String summarize(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Bundle) {
            return "Bundle" + dumpBundle((Bundle) o);
        }
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean) {
            return String.valueOf(o);
        }
        // HealthNotificationBean：尽量取标题/正文，信息量最高
        final String title = callString(o, "getTitle");
        final String content = callString(o, "getContent");
        if (title != null || content != null) {
            return simpleName(o.getClass().getName())
                    + "{title=" + title + ", content=" + content
                    + ", pkg=" + callString(o, "getPackageName") + "}";
        }
        // protobuf Builder：打印已设置字段数即可
        if (o.getClass().getName().endsWith("Builder")) {
            final String built = callString(o, "toString");
            return simpleName(o.getClass().getName())
                    + "(" + (built == null ? "?" : built.length() + " chars") + ")";
        }
        return simpleName(o.getClass().getName());
    }

    private static String callString(Object target, String getter) {
        try {
            final Method m = target.getClass().getMethod(getter);
            m.setAccessible(true);
            final Object v = m.invoke(target);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String dumpBundle(Bundle bundle) {
        final Set<String> keys = bundle.keySet();
        final StringBuilder sb = new StringBuilder(keys.size() * 40).append('{');
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(key).append('=');
            try {
                final Object v = bundle.get(key);
                if (v instanceof byte[]) {
                    sb.append("byte[").append(((byte[]) v).length).append(']');
                } else if (v instanceof Bundle) {
                    sb.append("Bundle").append(dumpBundle((Bundle) v));
                } else if (v instanceof java.util.List) {
                    sb.append("List(size=").append(((java.util.List<?>) v).size()).append(')');
                } else {
                    sb.append(v);
                }
            } catch (Throwable t) {
                sb.append('<').append(t.getClass().getSimpleName()).append('>');
            }
        }
        return sb.append('}').toString();
    }

    private static String simpleName(String className) {
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
