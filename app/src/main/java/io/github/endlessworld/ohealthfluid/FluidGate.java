package io.github.endlessworld.ohealthfluid;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * 方案 A：让「设备 → 通知 → 流体云」设置页在非 ColorOS 环境下也能正常打开并显示开关。
 *
 * <p>该页面的数据来自 {@code FluidViewModel.loadData()}，它有四个环境依赖点，
 * 每个都对应这里的一个 Hook：
 *
 * <table border="1">
 *   <tr><th>#</th><th>依赖</th><th>Hook 点</th></tr>
 *   <tr><td>1</td><td>ColorOS 私有 Secure Settings 三个开关</td>
 *       <td>{@code AppSettings$Secure.getInt} / {@code FluidViewModel.getMainSwitch}</td></tr>
 *   <tr><td>2</td><td>手表 AIDL 回包（支持应用列表）</td>
 *       <td>{@code NotificationTransportApis.getFluidSupportApps}</td></tr>
 *   <tr><td>3</td><td>ColorOS UMS Provider（品牌列表）</td>
 *       <td>{@code ContentResolver.call}</td></tr>
 *   <tr><td>4</td><td>网络状态误判导致回落异常页</td>
 *       <td>{@code NetworkUtil.isNetworkEnable}</td></tr>
 * </table>
 */
final class FluidGate {

    private static final String TAG = "OHealthFluidUnlock";

    /** ColorOS 侧流体云总开关相关的三个 Secure Settings 键。 */
    private static final Set<String> SWITCH_KEYS = new HashSet<>(Arrays.asList(
            "metis_strengthen_service_toggle",   // Metis 场景引擎
            "key_com_coloros_sceneservice",      // SceneService
            "fluid_seeding_enable_wizard"        // 流体云总开关
    ));

    private static final String UMS_AUTHORITY = "com.oplus.pantanal.ums.settings";
    private static final String UMS_METHOD = "query_brand_list";
    private static final String UMS_RESULT_KEY = "result";

    private static volatile boolean brandListLoggedOnce;
    private static volatile boolean appListLoggedOnce;

    private FluidGate() {
    }

    static void install(ClassLoader cl, XposedInterface xp) {
        // 该拦截最彻底：直接替换 loadData 的结果，其它 hook 作为补充
        hookLoadData(cl, xp);
        hookSwitches(cl, xp);
        hookTransport(cl, xp);
        hookUmsProvider(cl, xp);
        hookNetwork(cl, xp);
    }

    // ------------------------------------------------------------------
    // 0. 决定性拦截：直接接管 FluidViewModel.loadData 的返回值
    // ------------------------------------------------------------------

    /**
     * {@code loadData(SavedStateHandle, Continuation)} 是 suspend 方法。
     * 直接返回 UI 数据对象即可（非 COROUTINE_SUSPENDED 时 Kotlin 状态机会同步取值），
     * 从而完全绕过 UMS Provider / 手表 AIDL / 网络判定。
     */
    private static void hookLoadData(ClassLoader cl, XposedInterface xp) {
        try {
            final Class<?> vm = Class.forName(
                    "com.heytap.health.watch.notification.impl.ui.FluidViewModel", false, cl);
            final Class<?> handle = Class.forName("androidx.lifecycle.SavedStateHandle", false, cl);
            final Class<?> cont = Class.forName("kotlin.coroutines.Continuation", false, cl);

            final Method loadData = vm.getDeclaredMethod("loadData", handle, cont);
            loadData.setAccessible(true);

            xp.hook(loadData).intercept(chain -> {
                final Object uiData = FluidUiFactory.build(cl);
                if (uiData != null) {
                    Log.i(TAG, "[GATE] FluidViewModel#loadData -> injected FluidUiData");
                    return uiData;
                }
                // 构造失败时退回原实现，至少保留原有行为便于排查
                Log.w(TAG, "loadData: inject failed, falling back to original");
                return chain.proceed();
            });
            Log.i(TAG, "hooked loadData : FluidViewModel#loadData (decisive)");
        } catch (Throwable t) {
            Log.i(TAG, "SKIP FluidViewModel#loadData: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 1. 三个开关：读得到、写得进
    // ------------------------------------------------------------------

    private static void hookSwitches(ClassLoader cl, XposedInterface xp) {
        // 1a) 让 Secure Settings 的读取返回 1（只针对流体云相关键，避免影响其它设置）
        try {
            final Class<?> secure = Class.forName("com.oplus.providers.AppSettings$Secure", false, cl);
            final Method getInt = secure.getDeclaredMethod(
                    "getInt", android.content.ContentResolver.class, String.class, int.class);
            getInt.setAccessible(true);
            xp.hook(getInt).intercept(chain -> {
                final Object key = chain.getArg(1);
                if (key instanceof String && SWITCH_KEYS.contains(key)) {
                    Log.i(TAG, "[GATE] Secure.getInt(" + key + ") -> 1");
                    return 1;
                }
                return chain.proceed();
            });
            Log.i(TAG, "hooked switch get : AppSettings$Secure#getInt");

            // 1b) 让开关的写入假装成功，使用户的拨动至少不报错
            try {
                final Method putInt = secure.getDeclaredMethod(
                        "putInt", android.content.ContentResolver.class, String.class, int.class);
                putInt.setAccessible(true);
                xp.hook(putInt).intercept(chain -> {
                    final Object key = chain.getArg(1);
                    if (key instanceof String && SWITCH_KEYS.contains(key)) {
                        Log.i(TAG, "[GATE] Secure.putInt(" + key + ") -> true (not persisted)");
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "hooked switch put : AppSettings$Secure#putInt");
            } catch (Throwable t) {
                Log.i(TAG, "SKIP putInt: " + t.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            Log.i(TAG, "SKIP AppSettings$Secure: " + t.getClass().getSimpleName());
        }

        // 1c) 兜底：无论 Settings 是否读得到，主开关一律为开
        try {
            final Class<?> vm = Class.forName(
                    "com.heytap.health.watch.notification.impl.ui.FluidViewModel", false, cl);
            final Method getMainSwitch = vm.getDeclaredMethod("getMainSwitch");
            getMainSwitch.setAccessible(true);
            xp.hook(getMainSwitch).intercept(chain -> {
                Log.i(TAG, "[GATE] FluidViewModel#getMainSwitch -> true");
                return Boolean.TRUE;
            });
            Log.i(TAG, "hooked main switch: FluidViewModel#getMainSwitch");
        } catch (Throwable t) {
            Log.i(TAG, "SKIP FluidViewModel#getMainSwitch: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 2. 手表 AIDL：直接喂回包，不等手表
    // ------------------------------------------------------------------

    private static void hookTransport(ClassLoader cl, XposedInterface xp) {
        try {
            final Class<?> apis = Class.forName(
                    "com.heytap.health.watch.notification.NotificationTransportApis", false, cl);

            final Class<?> callback = Class.forName(
                    "com.heytap.health.watch.notification.INotificationBundleCallback", false, cl);

            final Method getFluidSupportApps = apis.getDeclaredMethod("getFluidSupportApps", callback);
            getFluidSupportApps.setAccessible(true);

            xp.hook(getFluidSupportApps).intercept(chain -> {
                final Object cb = chain.getArg(0);
                final Bundle reply = new Bundle();
                reply.putString("key_fluid_app_list_json", FakeData.fluidAppListJson(cl));

                if (!appListLoggedOnce) {
                    appListLoggedOnce = true;
                    Log.i(TAG, "[GATE] getFluidSupportApps -> injecting fake support-app list");
                }
                deliver(cb, reply);
                return null; // 跳过原始调用，不再等待手表 AIDL
            });
            Log.i(TAG, "hooked transport : NotificationTransportApis#getFluidSupportApps");
        } catch (Throwable t) {
            Log.i(TAG, "SKIP getFluidSupportApps: " + t.getClass().getSimpleName());
        }
    }

    /** 反射调用回调的 {@code onResult(Bundle)}，沿继承链查找。 */
    private static void deliver(Object callback, Bundle bundle) {
        if (callback == null) {
            Log.w(TAG, "deliver: callback is null");
            return;
        }
        for (Class<?> c = callback.getClass(); c != null; c = c.getSuperclass()) {
            try {
                final Method m = c.getDeclaredMethod("onResult", Bundle.class);
                m.setAccessible(true);
                m.invoke(callback, bundle);
                return;
            } catch (NoSuchMethodException ignored) {
                // 继续向父类找
            } catch (Throwable t) {
                Log.e(TAG, "deliver onResult failed: " + t);
                return;
            }
        }
        Log.w(TAG, "deliver: onResult(Bundle) not found on " + callback.getClass().getName());
    }

    // ------------------------------------------------------------------
    // 3. UMS Provider：拦截品牌列表查询
    // ------------------------------------------------------------------

    private static void hookUmsProvider(ClassLoader cl, XposedInterface xp) {
        try {
            final Class<?> cr = android.content.ContentResolver.class;
            final Method call = cr.getDeclaredMethod(
                    "call", Uri.class, String.class, String.class, Bundle.class);
            call.setAccessible(true);

            xp.hook(call).intercept(chain -> {
                // 注意：call() 是系统核心方法，本进程内所有 Provider 调用都会经过这里。
                // 因此先用最廉价的方式（引用比较）快速排除绝大多数无关调用，
                // 只有方法名匹配时才去解析 authority。
                final Object uriArg = chain.getArg(0);
                final Object methodArg = chain.getArg(1);

                if (UMS_METHOD != methodArg && !UMS_METHOD.equals(methodArg)) {
                    return chain.proceed();
                }
                if (!(uriArg instanceof Uri)) {
                    return chain.proceed();
                }
                if (!UMS_AUTHORITY.equals(((Uri) uriArg).getAuthority())) {
                    return chain.proceed();
                }

                final Bundle reply = new Bundle();
                reply.putString(UMS_RESULT_KEY, FakeData.brandListJson(cl));
                if (!brandListLoggedOnce) {
                    brandListLoggedOnce = true;
                    Log.i(TAG, "[GATE] ContentResolver.call(" + UMS_AUTHORITY
                            + "#" + UMS_METHOD + ") -> injecting fake brand list");
                }
                return reply;
            });
            Log.i(TAG, "hooked provider  : ContentResolver#call (ums.settings)");
        } catch (Throwable t) {
            Log.i(TAG, "SKIP ContentResolver#call: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 4. 网络状态：避免回落通用「无网络连接」异常页
    // ------------------------------------------------------------------

    private static void hookNetwork(ClassLoader cl, XposedInterface xp) {
        try {
            final Class<?> util = Class.forName("com.heytap.health.base.network.NetworkUtil", false, cl);
            final Method isNetworkEnable = util.getDeclaredMethod("isNetworkEnable");
            isNetworkEnable.setAccessible(true);
            xp.hook(isNetworkEnable).intercept(chain -> {
                Log.i(TAG, "[GATE] NetworkUtil#isNetworkEnable -> true");
                return Boolean.TRUE;
            });
            Log.i(TAG, "hooked network   : NetworkUtil#isNetworkEnable");
        } catch (Throwable t) {
            Log.i(TAG, "SKIP NetworkUtil#isNetworkEnable: " + t.getClass().getSimpleName());
        }
    }
}
