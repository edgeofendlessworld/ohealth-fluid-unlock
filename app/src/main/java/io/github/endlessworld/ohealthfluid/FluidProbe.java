package io.github.endlessworld.ohealthfluid;

import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * 只读探针：把「注入 → 转换 → 推送手表」这条链路逐段打出来。
 *
 * <p>要回答的问题只有一个：注入的 bundle 已经带上了 B1/C1（旧协议扁平键）与
 * KEY_TEXTS（新协议），到底是在哪一跳丢的。
 *
 * <p>日志一律走 {@link Sink} 回调到模块自身的 {@code log(...)}，这样日志行和
 * {@code [OBS]}/{@code [GATE]} 一样带 LSPosed 的模块前缀 —— 否则
 * {@code android.util.Log} 打出来的行标签是 {@code OHealthFluidUnlock}，
 * 在按模块前缀过滤的日志视图里会整片消失。
 */
final class FluidProbe {

    private static final String TAG = "OHealthFluidUnlock";

    /** 日志出口，由模块注入；为 null 时退回 android.util.Log。 */
    interface Sink {
        void log(String message);
    }

    private static Sink sSink;

    private static final String CONVERTER =
            "com.heytap.health.watch.notification.impl.transceiver.convert.ConverterWorker";
    private static final String CLOUD_PUSH =
            "com.heytap.health.watch.notification.impl.cloud.CloudPushManager";
    private static final String EVENT_MANAGER =
            "com.heytap.health.watch.notification.impl.transceiver.NotificationEventManager";
    private static final String REGISTER_CENTER =
            "com.heytap.health.watch.notification.HealthNotificationRegisterCenter";
    private static final String BEAN =
            "com.heytap.health.watch.notification.HealthNotificationBean";
    private static final String SEEDING_BUILDER =
            "com.heytap.health.watch.notification.SeedingCardStyleProto$Builder";
    private static final String PARSED_BUILDER =
            "com.heytap.health.watch.notification.ParsedNotificationProto$Builder";

    /** convertFluid 的四个实现类：注入后一定会命中其中一个。 */
    private static final String[] CONVERT_WORKERS = {
            "com.heytap.health.watch.notification.impl.transceiver.convert.Watch4GenConverterWorker",
            "com.heytap.health.watch.notification.impl.transceiver.convert.NormalConverterWorker",
            "com.heytap.health.watch.notification.impl.transceiver.convert.BleConverterWorker",
            "com.heytap.health.watch.notification.impl.transceiver.convert.IWatchConverterWorker",
    };

    private FluidProbe() {
    }

    static void install(ClassLoader cl, XposedInterface xp, Sink sink) {
        sSink = sink;
        // 第一行必须是可见的：如果这一行都看不到，说明 FluidProbe 根本没被加载，
        // 而不是「钩子没命中」。
        log("[PROBE] install() entered");

        // 0) 分发中心：注入后的第一站，也是最前的一跳
        observe(cl, xp, REGISTER_CENTER, "onNotificationFluid", BEAN, "android.os.Bundle");
        observe(cl, xp, EVENT_MANAGER, "onNotificationFluid", BEAN, "android.os.Bundle");

        // 1) 四个 convertFluid 实现：无论手表是哪一代，都会命中一个。
        //    这里同时把 ability 的三个世代开关打出来，直接判定走 newPb 还是 oldPb。
        for (String worker : CONVERT_WORKERS) {
            observeWithAbility(cl, xp, worker, "convertFluid", BEAN, "android.os.Bundle");
        }

        // 2) 新旧协议的填充点
        observe(cl, xp, CONVERTER, "convertFluidNew", BEAN, "android.os.Bundle", PARSED_BUILDER);
        observe(cl, xp, CONVERTER, "newPb", BEAN, "android.os.Bundle", SEEDING_BUILDER);
        observe(cl, xp, CONVERTER, "oldPb", "android.os.Bundle", SEEDING_BUILDER);

        // 3) 静默去重：重复卡片会被直接丢弃，是「什么都没有」的常见原因
        observe(cl, xp, CONVERTER, "isDuplicateFluidSeedingCard", "java.lang.String", SEEDING_BUILDER);

        // 4) 真正推送到手表
        observe(cl, xp, CLOUD_PUSH, "cloudPush", BEAN);

        log("[PROBE] install() done");
    }

    /**
     * 挂点，并在每次命中时额外打印 {@code ConverterWorker.ability} 的世代开关，
     * 用来判定 newPb / oldPb 分支。
     */
    private static void observeWithAbility(ClassLoader cl, XposedInterface xp,
                                           String className, String methodName,
                                           String... paramTypes) {
        try {
            final Class<?> cls = Class.forName(className, false, cl);
            final Method m = findMethod(cls, methodName, cl, paramTypes);
            if (m == null) {
                log("[PROBE] SKIP " + simpleName(className) + "#" + methodName);
                return;
            }
            m.setAccessible(true);
            final Field abilityField = findAbilityField(cl);

            final String label = simpleName(className) + "#" + methodName;
            xp.hook(m).intercept(chain -> {
                final Object result;
                try {
                    result = chain.proceed();
                } catch (Throwable t) {
                    log("[PROBE] " + label + " threw: " + t);
                    throw t;
                }
                final StringBuilder sb = new StringBuilder("[PROBE] ").append(label);
                for (Object a : chain.getArgs()) {
                    sb.append(" | ").append(summarize(a));
                }
                sb.append(" | ").append(describeAbility(abilityField));
                if (!isVoid(m)) {
                    sb.append(" => ").append(summarize(result));
                }
                log(sb.toString());
                return result;
            });
            log("[PROBE] installed : " + label);
        } catch (Throwable t) {
            log("[PROBE] SKIP " + simpleName(className) + "#" + methodName + ": "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /** {@code ConverterWorker.ability} 是 public static 字段，指向当前的设备能力对象。 */
    private static Field findAbilityField(ClassLoader cl) {
        try {
            final Field f = Class.forName(CONVERTER, false, cl).getField("ability");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 isNewPbField / isOldFluid / isSupportWebp 三个开关读出来。 */
    private static String describeAbility(Field abilityField) {
        if (abilityField == null) {
            return "ability=?";
        }
        try {
            final Object ability = abilityField.get(null);
            if (ability == null) {
                return "ability=null";
            }
            // 用字段声明类型（公开的能力接口）取方法，避免实现类不可访问导致 IllegalAccessException
            final Class<?> type = abilityField.getType();
            return "isNewPbField=" + invokeBool(type, ability, "isNewPbField")
                    + ", isOldFluid=" + invokeBool(type, ability, "isOldFluid")
                    + ", isSupportWebp=" + invokeBool(type, ability, "isSupportWebp");
        } catch (Throwable t) {
            return "ability=<" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String invokeBool(Class<?> type, Object target, String method) {
        try {
            final Method m = type.getMethod(method);
            m.setAccessible(true);
            return String.valueOf(m.invoke(target));
        } catch (Throwable t) {
            return "?";
        }
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
                log("[PROBE] SKIP " + simpleName(className) + "#" + methodName);
                return;
            }
            m.setAccessible(true);

            final String label = simpleName(className) + "#" + methodName;
            xp.hook(m).intercept(chain -> {
                final Object result;
                try {
                    result = chain.proceed();
                } catch (Throwable t) {
                    log("[PROBE] " + label + " threw: " + t);
                    throw t;
                }
                final StringBuilder sb = new StringBuilder("[PROBE] ").append(label);
                for (Object a : chain.getArgs()) {
                    sb.append(" | ").append(summarize(a));
                }
                if (!isVoid(m)) {
                    sb.append(" => ").append(summarize(result));
                }
                log(sb.toString());
                return result;
            });
            log("[PROBE] installed : " + label);
        } catch (Throwable t) {
            log("[PROBE] SKIP " + className + "#" + methodName + ": "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void log(String message) {
        final Sink sink = sSink;
        if (sink != null) {
            sink.log(message);
        } else {
            Log.i(TAG, message);
        }
    }

    /** 精确签名优先；失败则按方法名取第一个具体重载（容忍不同版本的参数差异）。 */
    private static Method findMethod(Class<?> cls, String name, ClassLoader cl, String... paramTypes) {
        try {
            final Class<?>[] types = new Class<?>[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                types[i] = Class.forName(paramTypes[i], false, cl);
            }
            return cls.getDeclaredMethod(name, types);
        } catch (NoSuchMethodException ignored) {
            // 退化为按名匹配
        } catch (Throwable t) {
            log("[PROBE] signature error " + name + ": " + t.getClass().getSimpleName());
            return null;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) {
                log("[PROBE] " + name + " matched by name (params differ)");
                return m;
            }
        }
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
        // HealthNotificationBean：标题/正文信息量最高
        final String title = callString(o, "getTitle");
        final String content = callString(o, "getContent");
        if (title != null || content != null) {
            return simpleName(o.getClass().getName())
                    + "{title=" + title + ", content=" + content
                    + ", pkg=" + callString(o, "getPackageName") + "}";
        }
        // protobuf Builder：打印序列化后的长度，能看出到底塞进去多少字段
        if (o.getClass().getName().endsWith("Builder")) {
            final String built = callString(o, "toString");
            return simpleName(o.getClass().getName())
                    + "(" + (built == null ? "?" : built.length() + " chars") + ")";
        }
        // List：逐项展开
        if (o instanceof List) {
            final List<?> list = (List<?>) o;
            final StringBuilder sb = new StringBuilder("List(size=").append(list.size()).append(")[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(describeElement(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return simpleName(o.getClass().getName());
    }

    /** TextBean / ImageBean 摘要：level + text 是判断层级对不对的直接证据。 */
    private static String describeElement(Object e) {
        if (e == null) {
            return "null";
        }
        final String level = callString(e, "getLevel");
        final String text = callString(e, "getText");
        if (level != null || text != null) {
            return simpleName(e.getClass().getName())
                    + "{level=" + level + ", text=" + text + "}";
        }
        return simpleName(e.getClass().getName());
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
                    final List<?> list = (List<?>) v;
                    sb.append("List(size=").append(list.size()).append(")[");
                    for (int i = 0; i < list.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(describeElement(list.get(i)));
                    }
                    sb.append(']');
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
