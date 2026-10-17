package io.github.endlessworld.ohealthfluid;

import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 直接构造流体云设置页所需的 {@code FluidUiData}，让页面不再依赖任何外部环境。
 *
 * <p>背景：{@code BasicStateViewModel.request()} 是状态机驱动的，逻辑等价于
 *
 * <pre>
 * showLoading()
 * val data = loadData(stateHandle)
 * if (data == null) { showError(SERVER_INTERNAL_ERROR); return }
 * showSucceed(data)
 * catch (Exception e) {
 *     if (e is NetDataErrorException) showError(SERVER_INTERNAL_ERROR.setMsg(e.message))
 *     else if (e is UIStateException) showError(e.state)
 *     else showError(default)        // ← 通用错误页：「无网络连接」
 * }
 * </pre>
 *
 * 而非 ColorOS 环境下，{@code FluidViewModel.loadData()} 里的
 * {@code ContentResolver.call("content://com.oplus.pantanal.ums.settings", ...)}
 * 因为 Provider 不存在会<b>抛 IllegalArgumentException</b>（不是返回 null），
 * 异常一路冒泡到上面的 {@code else} 分支，于是显示与网络无关的「无网络连接」。
 *
 * <p>因此最稳的做法是直接替换 {@code loadData} 的返回值。
 *
 * <p>实现上刻意<b>不猜测 Kotlin data class 的构造参数顺序</b>，
 * 而是复用 App 自身的 Gson（{@code GsonUtil.fromJson}）把 JSON 反序列化成
 * {@code BrandItem[]}，再用反射调用 {@code FluidUiData} 的四参构造函数。
 */
final class FluidUiFactory {

    private static final String TAG = "OHealthFluidUnlock";

    private static final String BRAND_ITEM =
            "com.heytap.health.watch.notification.impl.ui.BrandItem";
    private static final String FLUID_UI_DATA =
            "com.heytap.health.watch.notification.impl.ui.FluidUiData";
    private static final String GSON_UTIL =
            "com.heytap.health.base.text.GsonUtil";

    private FluidUiFactory() {
    }

    /**
     * 构造一个可用的 {@code FluidUiData}。
     *
     * @return 构造成功返回实例；任一环节失败返回 {@code null}，由调用方回退到原方法
     */
    static Object build(ClassLoader cl) {
        try {
            // 1) 用 App 自己的 Gson 把品牌列表 JSON 转成 BrandItem[]
            final Object brandArray = parseBrandArray(cl);
            final Object[] brands = brandArray == null
                    ? new Object[0]
                    : (Object[]) brandArray;

            // 2) FluidUiData(int tipImageRes, List installed, ArrayList moreApps, boolean mainSwitch)
            final Class<?> uiDataCls = Class.forName(FLUID_UI_DATA, false, cl);
            final Constructor<?> ctor = uiDataCls.getDeclaredConstructor(
                    int.class, List.class, ArrayList.class, boolean.class);
            ctor.setAccessible(true);

            // installed 与 moreApps 都要求非 null；用 ArrayList 保证可序列化
            final ArrayList<Object> installed = new ArrayList<>();
            final ArrayList<Object> moreApps = new ArrayList<>(brands.length);
            Collections.addAll(moreApps, brands);

            final Object uiData = ctor.newInstance(
                    0,              // tipImageRes：无引导图
                    installed,      // installed：留空，避免依赖已安装检测
                    moreApps,       // moreApps：全部白名单
                    Boolean.TRUE);  // mainSwitch：开

            Log.i(TAG, "FluidUiFactory: built FluidUiData with " + brands.length + " brand items");
            return uiData;
        } catch (Throwable t) {
            Log.e(TAG, "FluidUiFactory.build failed: " + t, t);
            return null;
        }
    }

    /** 反射调用 {@code GsonUtil.fromJson(String, Type)}，目标类型为 {@code BrandItem[]}。 */
    private static Object parseBrandArray(ClassLoader cl) throws Exception {
        final Class<?> brandItemCls = Class.forName(BRAND_ITEM, false, cl);
        final Class<?> gsonUtilCls = Class.forName(GSON_UTIL, false, cl);

        final Method fromJson = gsonUtilCls.getDeclaredMethod(
                "fromJson", String.class, java.lang.reflect.Type.class);
        fromJson.setAccessible(true);

        // BrandItem[] 的 Class 对象，无需泛型 TypeToken
        final Class<?> arrayType = Array.newInstance(brandItemCls, 0).getClass();

        final String json = FakeData.brandListJson(cl);
        final Object result = fromJson.invoke(null, json, arrayType);

        if (result == null) {
            Log.w(TAG, "FluidUiFactory: GsonUtil.fromJson returned null, json=" + json);
        }
        return result;
    }
}
