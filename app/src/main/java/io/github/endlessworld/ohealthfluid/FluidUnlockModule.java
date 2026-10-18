package io.github.endlessworld.ohealthfluid;

import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * OPPO 健康（com.heytap.health）流体云通道探针。
 *
 * <p>本模块做两件事：
 * <ol>
 *   <li><b>闸门</b>：把流体云的环境判定方法强制改为“支持”，让 App 内部的流体云管线继续往下走；</li>
 *   <li><b>探针</b>：在数据分发的关键节点打印日志，用于判断在非 OPPO 环境下
 *       App 自带白名单应用的数据究竟能不能流到“推送手表”这一步。</li>
 * </ol>
 *
 * <p>本模块做三件事：解锁流体云判定闸门、把白名单应用的通知注入成流体云卡片、
 * 在关键节点打印探针日志。卡片字段契约来自对 OPPO 健康 6.9.40 的反编译。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>不同 ROM / 版本下，哪些类覆写了能力判定方法并不一致，
 *       因此所有 hook 都做成<b>容错</b>：类或方法不存在只记 SKIP，不算错误。</li>
 *   <li>兜底手段：直接把 {@code FluidManager.supportFluid} 字段钉死为 true，
 *       不依赖任何单个判定方法是否被命中。</li>
 * </ul>
 */
public final class FluidUnlockModule extends XposedModule {

    private static final String TAG = "OHealthFluidUnlock";
    private static final String TARGET_PKG = "com.heytap.health";

    /** 用于给 [GATE] 日志去重，避免高频方法刷屏。 */
    private static final Set<String> GATE_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * 把卡片模板全局钉成 {@code center:compat}。
     *
     * <p>{@code ConverterWorker.newPb} 选模板：缺 {@code KEY_TYPES} 时落到
     * {@code "center:common"}；只有 {@code isNewPbFieldCompat()} 与
     * {@code FluidConfigCenter.compatMode(pkg)} 同时为真时才用 {@code "center:compat"}。
     *
     * <p>现在关掉：模板改由注入侧逐卡指派（{@code fluid_data} 里的
     * {@code probe_type_override}，由探针在 {@code newPb} 返回后覆盖 builder 上的 type）。
     * 全局开关会把每张卡都压成 compat，层级标签喷雾就分不出「模板选错」还是「层级写错」。
     */
    private static final boolean FORCE_COMPAT_TEMPLATE = false;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) {
            return;
        }

        final ClassLoader cl = param.getClassLoader();
        log(Log.INFO, TAG, "==================================================");
        log(Log.INFO, TAG, "injecting into " + TARGET_PKG
                + " (thread=" + Thread.currentThread().getName() + ")");
        log(Log.INFO, TAG, "framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + ", api=" + getApiVersion());

        hookGates(cl);
        hookObservers(cl);

        // 全链路只读探针：convertFluidNew / newPb / oldPb / cloudPush
        // 日志走模块自己的 log(...)，这样带 LSPosed 模块前缀，能和 [OBS]/[GATE] 一起看到；
        // 之前用 android.util.Log 打的是 OHealthFluidUnlock 标签，在按前缀过滤的视图里整片消失。
        try {
            FluidProbe.install(cl, this, msg -> log(Log.INFO, TAG, msg));
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "FluidProbe.install failed: " + t);
        }

        // 方案 A：解锁「设备 → 通知 → 流体云」设置页
        FluidGate.install(cl, this);

        // 兜住因 ColorOS 组件缺失导致的跳转崩溃
        CrashGuard.install(cl, this);

        // 方案 A 第二步：把白名单应用的通知桥接成流体云卡片
        NotificationBridge.install(cl, this);

        log(Log.INFO, TAG, "hook installation finished; watch for [GATE]/[OBS] lines");
        log(Log.INFO, TAG, "==================================================");
    }

    // ------------------------------------------------------------------
    // 一、闸门：让 App 认为当前环境支持流体云
    // ------------------------------------------------------------------

    private void hookGates(ClassLoader cl) {
        // 1) Seedling 支持判定的总入口（带 $ 的 Kotlin 内部方法名）
        hookBool(cl, "com.oplus.pantanal.seedling.util.BaseTool$Companion",
                "isSupport$seedling_support_manualRelease",
                "android.content.Context", "java.lang.String", "java.lang.String", "long");

        // 2) 真正决定 is_fluid_cloud_support 的 UMS 特性查询
        hookBool(cl, "com.oplus.seedling.sdk.utils.SeedlingIntentUtil",
                "isSupportFeatureFromUms",
                "android.content.Context", "java.lang.String", "java.lang.String");

        // 3) 对外暴露的流体云支持查询
        hookBool(cl, "com.oplus.seedling.sdk.SeedlingSdk",
                "isSupportFluidCloud", "android.content.Context");
        hookBool(cl, "com.oplus.pantanal.seedling.util.SeedlingTool",
                "isSupportFluidCloud", "android.content.Context");

        // 4) 能力判定：接口默认方法（主路径）
        final String base = "com.heytap.health.watch.notification.NotificationBaseAbility$Info";
        hookBool(cl, base, "isSupportFluid");
        hookBool(cl, base, "isSupportSecondFluid");
        hookInt(cl, base, "isDeviceSupportFluid", 1);

        // 5) 具体实现类：按方法名容错查找，缺失即跳过。
        //    不同机型/版本覆写情况不同，这里不做精确签名匹配。
        final String[] impls = {
                "com.heytap.health.watch.notification.impl.ability.NotificationAbility$Info",
                "com.heytap.health.deviceability.NotificationBaseAbilityDevice",
                "com.heytap.health.deviceability.NotificationAbilityDevice",
                "com.heytap.health.deviceability.DeviceEntranceAbilityDevice",
                "com.heytap.health.deviceability.OobeAbilityDevice",
                "com.heytap.health.deviceability.MoreSettingItemAbilityDevice",
        };
        for (String impl : impls) {
            hookBool(cl, impl, "isSupportFluid");
            hookBool(cl, impl, "isSupportSecondFluid");
            hookInt(cl, impl, "isDeviceSupportFluid", 1);
        }

        // 6) 兜底：把 FluidManager 的支持标志位钉死，摆脱对单个判定方法的依赖
        hookSupportFlag(cl);

        // 7) 卡片模板：让 newPb 选中 center:compat（镜像普通通知）而不是 center:common
        if (FORCE_COMPAT_TEMPLATE) {
            hookBool(cl, "com.heytap.health.watch.notification.impl.fluid.FluidConfigCenter",
                    "compatMode", "java.lang.String");
        }

        // 8) 震动档位：把 normalizeFluidBundle 硬写的“重要”盖回我们想要的档位
        hookNotifyLevel(cl);
    }

    /**
     * 震动档位。
     *
     * <p>{@code ExternalFluidCloudInjector.normalizeFluidBundle} 末尾会<b>无条件</b>
     * {@code putInt("KEY_NOTIFY_LEVEL", 1)}（= 重要），于是同一个导航通知每刷新一次，
     * 手表就震一下。这里在它返回之后再盖回去：
     * 只处理我们自己注入的 bundle（带 {@link FluidInjector#NOTIFY_LEVEL_KEY} 标记），
     * 原生卡片的档位一概不碰。
     */
    private void hookNotifyLevel(ClassLoader cl) {
        final String cls = "com.heytap.health.watch.notification.impl.fluid.external"
                + ".ExternalFluidCloudInjector";
        try {
            final Class<?> c = Class.forName(cls, false, cl);
            final Method m = c.getDeclaredMethod("normalizeFluidBundle", Bundle.class, String.class);
            m.setAccessible(true);
            hook(m).intercept(chain -> {
                final Object result = chain.proceed();
                if (result instanceof Bundle) {
                    final Bundle b = (Bundle) result;
                    if (b.containsKey(FluidInjector.NOTIFY_LEVEL_KEY)) {
                        final int level = b.getInt(FluidInjector.NOTIFY_LEVEL_KEY, 1);
                        b.putInt("KEY_NOTIFY_LEVEL", level);
                        logOnce("normalizeFluidBundle", "[GATE] KEY_NOTIFY_LEVEL -> " + level
                                + " (injector would force 1)");
                    }
                }
                return result;
            });
            log(Log.INFO, TAG, "hooked level : " + cls + "#normalizeFluidBundle");
        } catch (Throwable t) {
            logSoft("hookNotifyLevel", cls + "#normalizeFluidBundle", t);
        }
    }

    /**
     * 兜底闸门：{@code FluidManager.init()} 会把 {@code isSupportFluid()} 的结果写进
     * {@code supportFluid} 字段，这里在其返回后强制改写为 true。
     */
    private void hookSupportFlag(ClassLoader cl) {
        final String cls = "com.heytap.health.watch.notification.impl.fluid.FluidManager";
        try {
            final Class<?> c = Class.forName(cls, false, cl);

            final Method init = c.getDeclaredMethod("init");
            init.setAccessible(true);

            final Field flag = c.getDeclaredField("supportFluid");
            flag.setAccessible(true);

            hook(init).intercept(chain -> {
                final Object result = chain.proceed();
                final Object self = chain.getThisObject();
                if (self != null) {
                    try {
                        flag.setBoolean(self, true);
                        logOnce("FluidManager#init", "[GATE] FluidManager.supportFluid forced -> true"
                                + " (original init()=" + result + ")");
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "force supportFluid failed: " + t);
                    }
                }
                return result;
            });
            log(Log.INFO, TAG, "hooked flag  : " + cls + "#init -> supportFluid=true");
        } catch (Throwable t) {
            logSoft("hookSupportFlag", cls + "#init / supportFluid", t);
        }
    }

    // ------------------------------------------------------------------
    // 二、探针：观测流体云数据流
    // ------------------------------------------------------------------

    private void hookObservers(ClassLoader cl) {
        // A. 分发中心：推送给手表的必经之路，所有卡片都会经过这里
        observe(cl, "com.heytap.health.watch.notification.HealthNotificationRegisterCenter",
                "onNotificationFluid",
                "com.heytap.health.watch.notification.HealthNotificationBean", "android.os.Bundle");

        // B. 卡片数据出口：App 自己生成流体云卡片的统一入口
        observe(cl, "com.heytap.health.watch.notification.impl.fluid.FluidManager", "sendCardData",
                "pantanal.app.bean.CardViewInfo",
                "com.oplus.seedling.sdk.seedling.SeedlingUIData",
                "boolean", "java.lang.Integer", "kotlin.jvm.functions.Function1");

        // C. 白名单查询：可直观看到哪些包名在请求流体云（命中=内置支持）
        observe(cl, "com.heytap.health.watch.notification.impl.fluid.FluidConfigCenter",
                "getSupportApp", "java.lang.String");

        // D. 外部注入通道：观察是否有人在用广播喂数据
        final String injector =
                "com.heytap.health.watch.notification.impl.fluid.external.ExternalFluidCloudInjector";
        observe(cl, injector, "doInject", "android.os.Bundle");
        observe(cl, injector, "doRemove", "android.os.Bundle");

        // E. doInject 内部的四个判定/转换点 —— 判断注入是「被设备能力拦下」
        //    还是「转换后字段为空」，这两者修法完全不同。
        observe(cl, injector, "isFluidTypeSupported", "android.os.Bundle");   // 设备能力闸门
        observe(cl, injector, "normalizeFluidBundle",
                "android.os.Bundle", "java.lang.String");                     // 真正下发的 bundle
        observe(cl, injector, "parseTextItems", "android.os.Bundle");         // fluid_texts → TextBean
        observe(cl, injector, "parseImageItems", "android.os.Bundle");        // fluid_images → ImageBean
        observe(cl, injector, "pickLargeIcon", "android.os.Bundle");          // D1/A1 图标

        // F. 手表世代：决定走 newPb(KEY_TEXTS) 还是 oldPb(B/B0/B1/C1..) 两套字段契约
        final String ability = "com.heytap.health.watch.notification.impl.ability.NotificationAbility$Info";
        observe(cl, ability, "isNewPbField");
        observe(cl, ability, "isOldFluid");
        observe(cl, ability, "isSupportWebp");
    }

    // ------------------------------------------------------------------
    // 通用 Hook 工具
    // ------------------------------------------------------------------

    /** 把返回 boolean 的方法强制为 true（精确签名，兼容按名查找）。 */
    private void hookBool(ClassLoader cl, String className, String methodName, String... paramTypes) {
        final Method m = findMethod(cl, className, methodName, paramTypes);
        if (m == null) {
            return;
        }
        hook(m).intercept(chain -> {
            // 先把真实返回值跑出来再覆盖：日志里的 original 才是设备的真实能力，
            // 是判断「这表到底支不支持」的唯一证据（只看 -> true 等于什么都没看到）。
            String original;
            try {
                original = String.valueOf(chain.proceed());
            } catch (Throwable t) {
                original = "threw:" + t.getClass().getSimpleName();
            }
            logOnce(className + "#" + methodName,
                    "[GATE] " + className + "#" + methodName + " -> true (original=" + original + ")");
            return Boolean.TRUE;
        });
        log(Log.INFO, TAG, "hooked bool  : " + className + "#" + methodName);
    }

    /** 把返回 int 的方法强制为指定值。 */
    private void hookInt(ClassLoader cl, String className, String methodName, int value, String... paramTypes) {
        final Method m = findMethod(cl, className, methodName, paramTypes);
        if (m == null) {
            return;
        }
        hook(m).intercept(chain -> {
            String original;
            try {
                original = String.valueOf(chain.proceed());
            } catch (Throwable t) {
                original = "threw:" + t.getClass().getSimpleName();
            }
            logOnce(className + "#" + methodName,
                    "[GATE] " + className + "#" + methodName + " -> " + value + " (original=" + original + ")");
            return value;
        });
        log(Log.INFO, TAG, "hooked int   : " + className + "#" + methodName);
    }

    /** 不改写行为，只记录入参与返回值。 */
    private void observe(ClassLoader cl, String className, String methodName, String... paramTypes) {
        final Method m = findMethod(cl, className, methodName, paramTypes);
        if (m == null) {
            return;
        }
        final String label = simpleName(className) + "#" + methodName;
        final boolean returnsVoid = m.getReturnType() == void.class;
        try {
            hook(m).intercept(chain -> {
                final Object result;
                try {
                    result = chain.proceed();
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "[OBS] " + label + " threw: " + t);
                    throw t;
                }
                final StringBuilder sb = new StringBuilder("[OBS] ").append(label);
                final List<Object> args = chain.getArgs();
                for (int i = 0; i < args.size(); i++) {
                    sb.append(i == 0 ? " (" : ", ").append(summarize(args.get(i)));
                }
                if (!args.isEmpty()) {
                    sb.append(')');
                }
                // void 方法不要打印 "=> null"，之前那条 doInject => null 是被这句误导的
                if (!returnsVoid) {
                    sb.append(" => ").append(summarize(result));
                }
                log(Log.INFO, TAG, sb.toString());
                return result;
            });
        } catch (Throwable t) {
            // 接口默认方法 / 抽象方法在某些框架下无法 hook：跳过，不影响其它探针
            log(Log.INFO, TAG, "SKIP observe " + label + ": " + t.getClass().getSimpleName());
            return;
        }
        log(Log.INFO, TAG, "observed     : " + className + "#" + methodName);
    }

    /**
     * 容错查找方法：
     * <ol>
     *   <li>先按精确签名找；</li>
     *   <li>找不到时退化为“按方法名找”（取第一个同名声明的重载），
     *       以适配不同 ROM 上参数列表的细微差异；</li>
     *   <li>都没有则记一条 SKIP 日志返回 null。</li>
     * </ol>
     */
    private Method findMethod(ClassLoader cl, String className, String methodName, String... paramTypes) {
        Class<?> c;
        try {
            c = Class.forName(className, false, cl);
        } catch (Throwable t) {
            logSoft(className, methodName, t);
            return null;
        }

        // 1) 精确签名
        try {
            final Method m = c.getDeclaredMethod(methodName, resolve(cl, paramTypes));
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
            // 继续尝试按名匹配
        } catch (Throwable t) {
            logSoft(className, methodName, t);
            return null;
        }

        // 2) 按名匹配（同名重载取第一个）
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                m.setAccessible(true);
                return m;
            }
        }

        // 3) 确实不存在：这是正常情况，不算错误
        log(Log.INFO, TAG, "SKIP (not declared): " + className + "#" + methodName);
        return null;
    }

    /**
     * 类或方法不存在属于预期情况（不同机型覆写不同），
     * 只记 INFO 级别的 SKIP；其它异常才记 ERROR。
     */
    private void logSoft(String className, String methodName, Throwable t) {
        if (t instanceof ClassNotFoundException || t instanceof NoSuchMethodException
                || t instanceof NoSuchFieldException) {
            log(Log.INFO, TAG, "SKIP (absent): " + className + "#" + methodName
                    + " :: " + t.getClass().getSimpleName());
        } else {
            log(Log.ERROR, TAG, "hook FAILED: " + className + "#" + methodName + " :: " + t);
        }
    }

    private void logOnce(String key, String message) {
        if (GATE_LOGGED.add(key)) {
            log(Log.INFO, TAG, message);
        }
    }

    private static Class<?>[] resolve(ClassLoader cl, String... names) throws ClassNotFoundException {
        final Class<?>[] out = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = resolveOne(cl, names[i]);
        }
        return out;
    }

    private static Class<?> resolveOne(ClassLoader cl, String name) throws ClassNotFoundException {
        switch (name) {
            case "boolean":
                return boolean.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            default:
                return Class.forName(name, false, cl);
        }
    }

    // ------------------------------------------------------------------
    // 日志格式化
    // ------------------------------------------------------------------

    private static String simpleName(String className) {
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String summarize(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Bundle) {
            return "Bundle" + dumpBundle((Bundle) o);
        }
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean) {
            return String.valueOf(o);
        }
        // parseTextItems / parseImageItems 返回的 ArrayList<TextBean|ImageBean>：
        // 逐项打印 level/text，这是判断「层级对不对」最直接的证据。
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
        if (o instanceof Parcelable) {
            return describeElement(o);
        }
        return o.getClass().getName();
    }

    /** Bean 类摘要：优先取 level + text（TextBean / ImageBean 都有 getLevel）。 */
    private static String describeElement(Object e) {
        if (e == null) {
            return "null";
        }
        try {
            final String level = callString(e, "getLevel");
            final String text = callString(e, "getText");
            if (level != null || text != null) {
                return simpleName(e.getClass().getName())
                        + "{level=" + level + ", text=" + text + "}";
            }
        } catch (Throwable ignored) {
            // 落到下面的通用分支
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

    /** 打印 Bundle 的全部键值，字节数组只显示长度。 */
    private static String dumpBundle(Bundle bundle) {
        final Set<String> keys = bundle.keySet();
        final StringBuilder sb = new StringBuilder(keys.size() * 32).append('{');
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
                } else {
                    sb.append(v);
                }
            } catch (Throwable t) {
                sb.append('<').append(t.getClass().getSimpleName()).append('>');
            }
        }
        return sb.append('}').toString();
    }
}
