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
 * <p>本版本<b>不伪造任何卡片数据</b>，只负责解锁判定 + 观测。
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

        // 方案 A：解锁「设备 → 通知 → 流体云」设置页
        FluidGate.install(cl, this);

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
        observe(cl, "com.heytap.health.watch.notification.impl.fluid.external.ExternalFluidCloudInjector",
                "doInject", "android.os.Bundle");
        observe(cl, "com.heytap.health.watch.notification.impl.fluid.external.ExternalFluidCloudInjector",
                "doRemove", "android.os.Bundle");
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
            logOnce(className + "#" + methodName,
                    "[GATE] " + className + "#" + methodName + " -> true");
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
            logOnce(className + "#" + methodName,
                    "[GATE] " + className + "#" + methodName + " -> " + value);
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
            sb.append(" => ").append(summarize(result));
            log(Log.INFO, TAG, sb.toString());
            return result;
        });
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
        if (o instanceof Parcelable) {
            return o.getClass().getSimpleName();
        }
        return o.getClass().getName();
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
