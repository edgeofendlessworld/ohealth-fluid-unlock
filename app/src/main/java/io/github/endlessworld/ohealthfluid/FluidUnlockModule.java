package io.github.endlessworld.ohealthfluid;

import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

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
 * <p>注意：本版本<b>不伪造任何卡片数据</b>，只负责解锁判定 + 观测。
 * 这样日志里出现的数据全部来自 App 自身逻辑，可信度最高。
 */
public final class FluidUnlockModule extends XposedModule {

    private static final String TAG = "OHealthFluidUnlock";
    private static final String TARGET_PKG = "com.heytap.health";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) {
            return;
        }

        final ClassLoader cl = param.getClassLoader();
        log(Log.INFO, TAG, "==================================================");
        log(Log.INFO, TAG, "injecting into " + TARGET_PKG + " (thread=" + Thread.currentThread().getName() + ")");
        log(Log.INFO, TAG, "framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + ", api=" + getApiVersion());

        hookGates(cl);
        hookObservers(cl);

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

        // 4) 手表能力判定（接口默认方法）
        final String info = "com.heytap.health.watch.notification.NotificationBaseAbility$Info";
        hookBool(cl, info, "isSupportFluid");
        hookBool(cl, info, "isSupportSecondFluid");
        hookInt(cl, info, "isDeviceSupportFluid", 1);

        // 5) 具体实现类：接口默认方法被覆写时的兜底
        final String impl = "com.heytap.health.watch.notification.impl.ability.NotificationAbility$Info";
        hookBool(cl, impl, "isSupportFluid");
        hookBool(cl, impl, "isSupportSecondFluid");
        hookInt(cl, impl, "isDeviceSupportFluid", 1);
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

    /** 把返回 boolean 的方法强制为 true。 */
    private void hookBool(ClassLoader cl, String className, String methodName, String... paramTypes) {
        try {
            final Method m = findMethod(cl, className, methodName, paramTypes);
            hook(m).intercept(chain -> {
                log(Log.INFO, TAG, "[GATE] " + className + "#" + methodName + " -> true");
                return Boolean.TRUE;
            });
            log(Log.INFO, TAG, "hooked bool  : " + className + "#" + methodName);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hookBool FAILED: " + className + "#" + methodName + " :: " + t);
        }
    }

    /** 把返回 int 的方法强制为指定值。 */
    private void hookInt(ClassLoader cl, String className, String methodName, int value, String... paramTypes) {
        try {
            final Method m = findMethod(cl, className, methodName, paramTypes);
            hook(m).intercept(chain -> {
                log(Log.INFO, TAG, "[GATE] " + className + "#" + methodName + " -> " + value);
                return value;
            });
            log(Log.INFO, TAG, "hooked int   : " + className + "#" + methodName);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hookInt FAILED: " + className + "#" + methodName + " :: " + t);
        }
    }

    /** 不改写行为，只记录入参与返回值。 */
    private void observe(ClassLoader cl, String className, String methodName, String... paramTypes) {
        try {
            final Method m = findMethod(cl, className, methodName, paramTypes);
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
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "observe FAILED: " + className + "#" + methodName + " :: " + t);
        }
    }

    private static Method findMethod(ClassLoader cl, String className, String methodName, String... paramTypes)
            throws ClassNotFoundException, NoSuchMethodException {
        final Class<?> c = Class.forName(className, false, cl);
        final Method m = c.getDeclaredMethod(methodName, resolve(cl, paramTypes));
        m.setAccessible(true);
        return m;
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
                sb.append("<").append(t.getClass().getSimpleName()).append('>');
            }
        }
        return sb.append('}').toString();
    }
}
