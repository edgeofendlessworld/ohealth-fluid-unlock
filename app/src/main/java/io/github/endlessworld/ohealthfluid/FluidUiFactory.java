package io.github.endlessworld.ohealthfluid;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造流体云设置页所需的 {@code FluidUiData}，复刻 {@code FluidViewModel.loadData()} 的分组逻辑。
 *
 * <pre>
 * for (brand in brandList) {                       // 来源：UMS query_brand_list
 *     appInfo = supportApps[brand.hostPkgName]     // 来源：手表 AIDL 回包
 *     if (appInfo == null) continue
 *     if (isIWatchOS && brand.hostPkgName !in IWATCH_FLUID_LISTS) continue
 *     if (isBand2   && brand.hostPkgName !in BAND2_FLUID_LISTS)  continue
 *
 *     name = isPkgInstall(brand, resolveInfos, supportApps)   // ← 已安装判定
 *     if (!name.isNullOrBlank()) installed += FluidInstalledItem(brand, name, summary, appInfo)
 *     else if (!brand.systemApp)  moreApps  += brand           // 显示"需安装"
 * }
 * FluidUiData(tipImgRes, installed, moreApps, mainSwitch)
 * </pre>
 *
 * <p>为什么自己复刻 {@code isPkgInstall} 而不反射调用它：它是 {@code FluidViewModel} 的
 * <b>实例方法</b>，而构造一个可用的 ViewModel 实例需要 {@code SavedStateHandle}，
 * 成本与风险都高于直接复刻这段十余行的判定。
 */
final class FluidUiFactory {

    private static final String TAG = "OHealthFluidUnlock";

    private static final String UI_PKG = "com.heytap.health.watch.notification.impl.ui.";
    private static final String BRAND_ITEM = UI_PKG + "BrandItem";
    private static final String INSTALLED_ITEM = UI_PKG + "FluidInstalledItem";
    private static final String FLUID_UI_DATA = UI_PKG + "FluidUiData";

    private static final String FLUID_APP_INFO =
            "com.heytap.health.watch.notification.impl.fluid.FluidAppInfo";
    private static final String FLUID_CONFIG_CENTER =
            "com.heytap.health.watch.notification.impl.fluid.FluidConfigCenter";
    private static final String GSON_UTIL = "com.heytap.health.base.text.GsonUtil";

    private FluidUiFactory() {
    }

    /** @return {@code FluidUiData}；失败返回 {@code null}，由调用方回退原实现。 */
    static Object build(ClassLoader cl) {
        try {
            final Object[] brands = parseBrandArray(cl);
            if (brands == null) {
                Log.w(TAG, "FluidUiFactory: brand list parse failed");
                return null;
            }

            final Map<Object, Object> supportApps = resolveSupportApps(cl);
            final List<ResolveInfo> resolveInfos = queryLauncherActivities();
            final PackageManager pm = packageManager();

            // 机型子集过滤：仅在确认为对应机型时才生效
            final Boolean[] flags = resolveDeviceFlags(cl);
            final boolean isIWatch = flags != null && Boolean.TRUE.equals(flags[0]);
            final boolean isBand2 = flags != null && Boolean.TRUE.equals(flags[1]);
            final List<Object> iwatchList = isIWatch ? resolveListField(cl, "getIWATCH_FLUID_LISTS") : null;
            final List<Object> band2List = isBand2 ? resolveListField(cl, "getBAND2_FLUID_LISTS") : null;

            final ArrayList<Object> installed = new ArrayList<>();
            final ArrayList<Object> moreApps = new ArrayList<>();

            int matched = 0;
            int filtered = 0;
            for (Object brand : brands) {
                if (brand == null) {
                    continue;
                }
                final String hostPkg = str(invoke(brand, "getHostPkgName"));
                final String brandCode = str(invoke(brand, "getBrandCode"));

                final Object appInfo = supportApps.get(hostPkg);
                if (appInfo == null) {
                    continue;   // 不在白名单
                }
                matched++;

                if (iwatchList != null && !iwatchList.contains(hostPkg)) {
                    filtered++;
                    continue;
                }
                if (band2List != null && !band2List.contains(hostPkg)) {
                    filtered++;
                    continue;
                }

                final String label = pkgInstallLabel(brand, brandCode, hostPkg, resolveInfos, supportApps, pm);

                if (label != null && !label.trim().isEmpty()) {
                    final Object item = buildInstalledItem(cl, brand, label, appInfo);
                    if (item != null) {
                        installed.add(item);
                    }
                } else if (!bool(invoke(brand, "getSystemApp"))) {
                    moreApps.add(brand);    // 未安装 → "更多应用"
                }
            }

            Log.i(TAG, "FluidUiFactory: brands=" + brands.length
                    + ", matched=" + matched
                    + ", filteredByModel=" + filtered
                    + ", installed=" + installed.size()
                    + ", moreApps=" + moreApps.size()
                    + ", iWatch=" + isIWatch + ", band2=" + isBand2);

            return buildUiData(cl, installed, moreApps);
        } catch (Throwable t) {
            Log.e(TAG, "FluidUiFactory.build failed: " + t, t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 已安装判定：忠实复刻 FluidViewModel.isPkgInstall
    // ------------------------------------------------------------------

    /**
     * 原始逻辑：
     * <pre>
     * var pkg = brand.hostPkgName
     * if (supportApps.keys.contains(brand.brandCode)) return brand.brandName
     * for (ri in resolveInfos) {
     *     if (ri.activityInfo?.packageName.equals(pkg, ignoreCase = true))
     *         return ri.loadLabel(pm).toString()
     * }
     * return null
     * </pre>
     */
    private static String pkgInstallLabel(Object brand, String brandCode, String hostPkg,
                                          List<ResolveInfo> resolveInfos,
                                          Map<Object, Object> supportApps,
                                          PackageManager pm) {
        // ① brandCode 命中白名单键 → 视为已安装，直接返回品牌名
        if (supportApps.containsKey(brandCode)) {
            return str(invoke(brand, "getBrandName"));
        }

        // ② 按包名匹配带 LAUNCHER 入口的 Activity
        if (pm == null || hostPkg.isEmpty()) {
            return null;
        }
        for (ResolveInfo ri : resolveInfos) {
            if (ri.activityInfo == null) {
                continue;
            }
            if (hostPkg.equalsIgnoreCase(ri.activityInfo.packageName)) {
                try {
                    final CharSequence label = ri.loadLabel(pm);
                    if (label != null) {
                        return label.toString();
                    }
                } catch (Throwable ignored) {
                    // loadLabel 失败时退回品牌名
                }
                return str(invoke(brand, "getBrandName"));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 数据源
    // ------------------------------------------------------------------

    /** 反射调用 {@code GsonUtil.fromJson(String, Type)}，目标类型 {@code BrandItem[]}。 */
    private static Object[] parseBrandArray(ClassLoader cl) throws Exception {
        final Class<?> brandItemCls = Class.forName(BRAND_ITEM, false, cl);
        final Class<?> gsonUtilCls = Class.forName(GSON_UTIL, false, cl);

        final Method fromJson = gsonUtilCls.getDeclaredMethod(
                "fromJson", String.class, java.lang.reflect.Type.class);
        fromJson.setAccessible(true);

        final Class<?> arrayType = Array.newInstance(brandItemCls, 0).getClass();
        return (Object[]) fromJson.invoke(null, FakeData.brandListJson(cl), arrayType);
    }

    /** {@code FluidConfigCenter.SUPPORT_APPS}：{@code Map<hostPkgName, FluidAppInfo>}。 */
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> resolveSupportApps(ClassLoader cl) {
        try {
            final Class<?> cc = Class.forName(FLUID_CONFIG_CENTER, false, cl);
            final Object self = cc.getField("INSTANCE").get(null);
            final Method m = cc.getDeclaredMethod("getSUPPORT_APPS");
            m.setAccessible(true);
            final Object map = m.invoke(self);
            if (map instanceof Map) {
                return (Map<Object, Object>) map;
            }
        } catch (Throwable t) {
            Log.w(TAG, "SUPPORT_APPS unavailable: " + t.getClass().getSimpleName());
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> resolveListField(ClassLoader cl, String getter) {
        try {
            final Class<?> cc = Class.forName(FLUID_CONFIG_CENTER, false, cl);
            final Object self = cc.getField("INSTANCE").get(null);
            final Method m = cc.getDeclaredMethod(getter);
            m.setAccessible(true);
            final Object list = m.invoke(self);
            if (list instanceof List) {
                return (List<Object>) list;
            }
        } catch (Throwable ignored) {
            // 取不到就不做机型过滤
        }
        return null;
    }

    /**
     * 判断当前连接设备的机型：{@code [isIWatchOS, isBand2]}。
     * 任一步失败返回 {@code null}（表示无法判断，此时不做机型过滤）。
     */
    private static Boolean[] resolveDeviceFlags(ClassLoader cl) {
        try {
            final Class<?> dm = Class.forName(
                    "com.heytap.health.devicemanager.client.DMHeytap", false, cl);
            final Object managerApi = dm.getField("managerApi").get(null);
            if (managerApi == null) {
                return null;
            }

            // managerApi 的运行时类型是接口 DMManagerApi，需按接口 Class 取方法
            final Class<?> managerApiCls = Class.forName(
                    "com.heytap.health.devicemanager.client.api.DMManagerApi", false, cl);
            final Class<?> roleCls = Class.forName(
                    "com.heytap.health.devicemanager.client.role.DeviceBasegetRole", false, cl);
            final Class<?> roleAllCls = Class.forName(
                    "com.heytap.health.devicemanager.client.role.DeviceBasegetRole$All", false, cl);

            final Object roleInstance = roleAllCls.getField("INSTANCE").get(null);

            final Method getMac = managerApiCls.getMethod("getCurrActiveMacByRole", roleCls);
            getMac.setAccessible(true);
            final Object mac = getMac.invoke(managerApi, roleInstance);
            if (!(mac instanceof String)) {
                return null;
            }

            final Class<?> tool = Class.forName(
                    "com.heytap.health.deviceability.NotificationAbilityTool", false, cl);
            final Method buildByMac = tool.getDeclaredMethod("buildByMac", String.class);
            buildByMac.setAccessible(true);
            final Object ability = buildByMac.invoke(null, mac);
            if (ability == null) {
                return null;
            }

            return new Boolean[]{
                    Boolean.valueOf(bool(invoke(ability, "isIWatchOS"))),
                    Boolean.valueOf(bool(invoke(ability, "isBand2")))
            };
        } catch (Throwable t) {
            Log.i(TAG, "device flags unavailable (" + t.getClass().getSimpleName()
                    + "), skipping model filter");
            return null;
        }
    }

    private static List<ResolveInfo> queryLauncherActivities() {
        try {
            final Context ctx = appContext();
            if (ctx == null) {
                return Collections.emptyList();
            }
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            final List<ResolveInfo> list = ctx.getPackageManager().queryIntentActivities(intent, 0);
            return list == null ? Collections.emptyList() : list;
        } catch (Throwable t) {
            Log.w(TAG, "queryIntentActivities failed: " + t.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private static PackageManager packageManager() {
        final Context ctx = appContext();
        return ctx == null ? null : ctx.getPackageManager();
    }

    private static Context appContext() {
        try {
            final Class<?> at = Class.forName("android.app.ActivityThread");
            final Method current = at.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            final Object app = current.invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
            // 退回 App 自己的持有类
        }
        try {
            final Class<?> holder = Class.forName(
                    "com.heytap.health.base.base.GlobalApplicationHolder");
            final Method get = holder.getDeclaredMethod("getAppContext");
            get.setAccessible(true);
            final Object ctx = get.invoke(null);
            if (ctx instanceof Context) {
                return (Context) ctx;
            }
        } catch (Throwable ignored) {
            // 两条路都失败
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 对象构造
    // ------------------------------------------------------------------

    /** {@code FluidInstalledItem(BrandItem, String appName, String summary, FluidAppInfo)} */
    private static Object buildInstalledItem(ClassLoader cl, Object brand,
                                             String appName, Object appInfo) {
        try {
            final Class<?> itemCls = Class.forName(INSTALLED_ITEM, false, cl);
            final Class<?> brandCls = Class.forName(BRAND_ITEM, false, cl);
            final Class<?> appInfoCls = Class.forName(FLUID_APP_INFO, false, cl);

            final Constructor<?> ctor = itemCls.getDeclaredConstructor(
                    brandCls, String.class, String.class, appInfoCls);
            ctor.setAccessible(true);

            final Object summary = invoke(appInfo, "getFixedSummary");
            return ctor.newInstance(brand, appName,
                    summary == null ? "" : String.valueOf(summary), appInfo);
        } catch (Throwable t) {
            Log.w(TAG, "buildInstalledItem failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** {@code FluidUiData(int tipImageRes, List installed, ArrayList moreApps, boolean mainSwitch)} */
    private static Object buildUiData(ClassLoader cl, ArrayList<Object> installed,
                                      ArrayList<Object> moreApps) throws Exception {
        final Class<?> uiDataCls = Class.forName(FLUID_UI_DATA, false, cl);
        final Constructor<?> ctor = uiDataCls.getDeclaredConstructor(
                int.class, List.class, ArrayList.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(0, installed, moreApps, Boolean.TRUE);
    }

    // ------------------------------------------------------------------
    // 反射小工具
    // ------------------------------------------------------------------

    private static Object invoke(Object target, String getter) {
        if (target == null) {
            return null;
        }
        try {
            final Method m = target.getClass().getMethod(getter);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean && (Boolean) o;
    }
}
