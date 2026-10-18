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

    /** protobuf 的元方法，打印字段时跳过。 */
    private static final Set<String> SCALAR_SKIP = new java.util.HashSet<>(java.util.Arrays.asList(
            "getClass", "getSerializedSize", "getDefaultInstanceForType", "getParserForType",
            "getDescriptor", "getDescriptorForType", "getUnknownFields"));

    private static final String CONVERTER =
            "com.heytap.health.watch.notification.impl.transceiver.convert.ConverterWorker";
    private static final String CLOUD_PUSH =
            "com.heytap.health.watch.notification.impl.cloud.CloudPushManager";
    private static final String EVENT_MANAGER =
            "com.heytap.health.watch.notification.impl.transceiver.NotificationEventManager";
    private static final String FLUID_MANAGER =
            "com.heytap.health.watch.notification.impl.fluid.FluidManager";
    private static final String REGISTER_CENTER =
            "com.heytap.health.watch.notification.HealthNotificationRegisterCenter";    private static final String BEAN =
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
        // newPb 需要「观测 + 改写 type」，所以用专用实现而不是通用的只读 observe
        assertCompatType(cl, xp);
        observe(cl, xp, CONVERTER, "oldPb", "android.os.Bundle", SEEDING_BUILDER);

        // 3) 静默去重：重复卡片会被直接丢弃，是「什么都没有」的常见原因
        observe(cl, xp, CONVERTER, "isDuplicateFluidSeedingCard", "java.lang.String", SEEDING_BUILDER);

        // 3.5) 参考路径：OPPO 自己的 sendCardData。
        //      用它对照就能知道「真卡片长什么样」，以及官方链路此刻是否也在出卡。
        observe(cl, xp, FLUID_MANAGER, "sendCardData");

        // 4) 真正推送到手表
        observe(cl, xp, CLOUD_PUSH, "cloudPush", BEAN);

        log("[PROBE] install() done");
    }

    /** {@code "center:compat"}：把一条普通通知镜像成卡片的模板，对字段要求最少。 */
    private static final String COMPAT_TYPE = "center:compat";

    /**
     * 观测 {@code ConverterWorker#newPb}，并把它在 builder 上设好的 {@code type} 改成
     * {@code center:compat}。
     *
     * <p>为什么在返回之后改：{@code newPb} 自己按
     * {@code isNewPbFieldCompat() && compatMode(pkg)} 选模板，缺 {@code KEY_TYPES} 时落到
     * {@code center:common}。这里不跟那两个开关周旋，直接在 builder 上覆盖 ——
     * {@code type} 只是 builder 的一个字段，后面 {@code commonPb}/{@code build()} 都不会重设它。
     */
    private static void assertCompatType(ClassLoader cl, XposedInterface xp) {
        try {
            final Class<?> cls = Class.forName(CONVERTER, false, cl);
            final Method m = findMethod(cls, "newPb", cl, BEAN, "android.os.Bundle", SEEDING_BUILDER);
            if (m == null) {
                log("[PROBE] SKIP ConverterWorker#newPb");
                return;
            }
            m.setAccessible(true);

            xp.hook(m).intercept(chain -> {
                final Object result = chain.proceed();
                Object builder = null;
                for (Object a : chain.getArgs()) {
                    if (a != null && a.getClass().getName().contains("SeedingCardStyleProto$Builder")) {
                        builder = a;
                    }
                }
                String before = null;
                if (builder != null) {
                    before = callString(builder, "getType");
                    if (!COMPAT_TYPE.equals(before)) {
                        setType(builder, COMPAT_TYPE);
                    }
                }
                log("[PROBE] ConverterWorker#newPb | " + summarize(builder)
                        + " | type " + before + " -> " + COMPAT_TYPE);
                return result;
            });
            log("[PROBE] installed : ConverterWorker#newPb (with type override)");
        } catch (Throwable t) {
            log("[PROBE] SKIP ConverterWorker#newPb: "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void setType(Object builder, String type) {
        try {
            final Method m = builder.getClass().getMethod("setType", String.class);
            m.setAccessible(true);
            m.invoke(builder, type);
        } catch (Throwable t) {
            log("[PROBE] setType failed: " + t.getClass().getSimpleName());
        }
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
                    + ", isNewPbFieldCompat=" + invokeBool(type, ability, "isNewPbFieldCompat")
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
        // protobuf Builder：构建出来之后把 texts/images 逐项展开，
        // 这是「到底往手表送了哪些层级」的唯一直接证据
        if (o.getClass().getName().endsWith("Builder")) {
            return describeProto(o);
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

    /**
     * protobuf 消息/Builder 摘要：把 {@code get*List()} 里的元素逐个展开。
     *
     * <p>对 {@code SeedingCardStyleProto} 来说，{@code getTextsList()} 就是发到手表上的
     * 文本层级列表 —— 看到 {@code TextElement{level=B1, text=…}} 才说明链路真的通了。
     */
    private static String describeProto(Object builderOrProto) {
        Object proto = builderOrProto;
        try {
            final Method build = builderOrProto.getClass().getMethod("build");
            build.setAccessible(true);
            proto = build.invoke(builderOrProto);
        } catch (Throwable ignored) {
            // 已经是 proto 本体，直接用
        }
        if (proto == null) {
            return simpleName(builderOrProto.getClass().getName()) + "(empty)";
        }
        final StringBuilder sb = new StringBuilder(simpleName(proto.getClass().getName()));
        try {
            // 标量字段：type / notifyLevel / dismissOnce / dataSourcePkg 这些决定
            // 手表选哪套模板，必须看到
            int scalars = 0;
            for (Method m : proto.getClass().getMethods()) {
                final String name = m.getName();
                if (!name.startsWith("get") || name.endsWith("List") || m.getParameterCount() != 0) {
                    continue;
                }
                if (SCALAR_SKIP.contains(name)) {
                    continue;
                }
                final Class<?> ret = m.getReturnType();
                if (ret != String.class && ret != int.class && ret != long.class
                        && ret != boolean.class && ret != float.class && ret != double.class) {
                    continue;
                }
                if (scalars >= 10) {
                    break;
                }
                m.setAccessible(true);
                final Object value = m.invoke(proto);
                sb.append(' ').append(Character.toLowerCase(name.charAt(3)))
                        .append(name.substring(4)).append('=').append(value);
                scalars++;
            }
            for (Method m : proto.getClass().getMethods()) {
                final String name = m.getName();
                if (!name.startsWith("get") || !name.endsWith("List") || m.getParameterCount() != 0) {
                    continue;
                }
                m.setAccessible(true);
                final Object value = m.invoke(proto);
                if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
                    continue;
                }
                final List<?> list = (List<?>) value;
                sb.append(' ').append(Character.toLowerCase(name.charAt(3)))
                        .append(name, 4, name.length() - 4).append("=[");
                final int shown = Math.min(list.size(), 6);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(describeElement(list.get(i)));
                }
                if (list.size() > shown) {
                    sb.append(", …+").append(list.size() - shown);
                }
                sb.append(']');
            }
        } catch (Throwable t) {
            sb.append(" <").append(t.getClass().getSimpleName()).append('>');
        }
        return sb.toString();
    }

    /** TextElement / ImagesElement / ButtonsElement 摘要：level 是判断层级对不对的直接证据。 */
    private static String describeElement(Object e) {
        if (e == null) {
            return "null";
        }
        final String level = callString(e, "getLevel");
        final String text = callString(e, "getText");
        final String imageKey = callString(e, "getImageKey");
        if (level == null && text == null && imageKey == null) {
            return simpleName(e.getClass().getName());
        }
        final StringBuilder sb = new StringBuilder(simpleName(e.getClass().getName())).append('{');
        boolean first = true;
        if (level != null) {
            sb.append("level=").append(level);
            first = false;
        }
        if (text != null) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("text=").append(text);
            first = false;
        }
        if (imageKey != null) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("imageKey=").append(imageKey);
            first = false;
        }
        // 颜色是「有卡片但全白」的关键：白字配浅底等于看不见
        final String color = callColor(e, "getColor");
        if (color != null) {
            sb.append(", color=").append(color);
            first = false;
        }
        final String bg = callColor(e, "getBgColor");
        if (bg != null) {
            sb.append(", bg=").append(bg);
        }
        return sb.append('}').toString();
    }

    /** 把 int 颜色打成 #AARRGGBB，便于和设计稿/官方卡片对照。 */
    private static String callColor(Object target, String getter) {
        try {
            final Method m = target.getClass().getMethod(getter);
            if (m.getReturnType() != int.class) {
                return null;
            }
            m.setAccessible(true);
            return String.format("#%08X", (Integer) m.invoke(target));
        } catch (Throwable t) {
            return null;
        }
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
