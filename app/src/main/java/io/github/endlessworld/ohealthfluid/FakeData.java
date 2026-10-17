package io.github.endlessworld.ohealthfluid;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * 伪造流体云设置页所需的两份数据。
 *
 * <p>设置页 {@code FluidViewModel.loadData()} 依赖两个外部数据源，在非 ColorOS 环境下都拿不到：
 * <ol>
 *   <li><b>手表 AIDL</b>：{@code NotificationTransportApis.getFluidSupportApps} →
 *       回包键 {@code key_fluid_app_list_json}，JSON 反序列化为 {@code List<FluidAppInfo>}；</li>
 *   <li><b>ColorOS UMS Provider</b>：{@code content://com.oplus.pantanal.ums.settings} 的
 *       {@code query_brand_list} → 回包键 {@code result}，JSON 反序列化为 {@code List<BrandItem>}。</li>
 * </ol>
 *
 * <p>本类不硬编码应用清单，而是<b>反射调用 App 自己的
 * {@code FluidConfigCenter.defaultApps()}</b>，因此会自动跟随内置白名单与云端下发配置。
 */
final class FakeData {

    private static final String TAG = "OHealthFluidUnlock";

    private static final String FLUID_CONFIG_CENTER =
            "com.heytap.health.watch.notification.impl.fluid.FluidConfigCenter";

    /** 反射取不到内置白名单时的保底条目（包名, 显示名, brandCode, systemService）。 */
    private static final String[][] FALLBACK = {
            {"com.baidu.BaiduMap", "百度地图", "10023", "0"},
            {"com.autonavi.minimap", "高德地图", "10041", "0"},
            {"com.sdu.didi.psnger", "滴滴出行", "10116", "0"},
            {"me.ele", "饿了么", "10054", "0"},
            {"com.sankuai.meituan", "美团", "10015", "0"},
            {"com.sankuai.meituan.takeoutnew", "美团外卖", "10177", "0"},
            {"com.jingdong.app.mall", "京东", "10019", "0"},
            {"com.taobao.taobao", "淘宝", "10035", "0"},
            {"com.umetrip.android.msky.app", "航旅纵横", "10049", "0"},
            {"com.crgt.ilife", "国铁吉讯", "10032", "0"},
            {"com.MobileTicket", "12306", "10137", "0"},
            {"com.eg.android.AlipayGphone", "支付宝", "10013", "0"},
            {"com.dianping.v1", "大众点评", "10074", "0"},
            {"com.jingyao.easybike", "哈啰", "10240", "0"},
            {"cn.damai", "大麦", "10191", "0"},
            {"com.taobao.movie.android", "淘票票", "10086", "0"},
            {"com.pupumall.customer", "朴朴超市", "10241", "0"},
            {"com.teamtalk.im", "TeamTalk", "10078", "0"},
            {"com.heytap.health:pickup", "取单码提醒", "", "1"},
            {"com.heytap.health:carlock", "离车安心提醒服务", "", "1"},
            {"com.heytap.health:safeguard", "出行守护", "268451926", "1"},
            {"com.heytap.health:touchwf", "一碰传表盘", "268452027", "1"},
    };

    private FakeData() {
    }

    // ------------------------------------------------------------------
    // 数据源 1：手表 AIDL 回包（List<FluidAppInfo>）
    // ------------------------------------------------------------------

    /**
     * 构造 {@code key_fluid_app_list_json}。
     *
     * <p>字段名与 {@code FluidAppInfo} 的私有字段一一对应，供 Gson 直接映射。
     * {@code systemService == 1} 的条目会被 App 归入 {@code supportSystemApps}（按 brandCode 索引），
     * 其余归入 {@code supportApps}（按 hostPkgName 索引）。
     */
    static String fluidAppListJson(ClassLoader cl) {
        final StringBuilder sb = new StringBuilder(2048).append('[');
        boolean first = true;

        for (App app : resolveApps(cl)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('{')
                    .append("\"hostPkgName\":\"").append(esc(app.hostPkgName)).append('"')
                    .append(",\"appName\":\"").append(esc(app.appName)).append('"')
                    .append(",\"summaryId\":0")
                    .append(",\"summary\":\"\"").append(",\"summaryEn\":\"\"")
                    .append(",\"summaryTr\":\"\"")
                    .append(",\"brandCode\":\"").append(esc(app.brandCode)).append('"')
                    .append(",\"systemService\":").append(app.systemService)
                    .append(",\"iconId\":0")
                    .append(",\"iconUrl\":\"\"")
                    .append(",\"serviceIdList\":[]")
                    .append(",\"appVersion\":0")
                    .append('}');
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------
    // 数据源 2：UMS 回包（List<BrandItem>）
    // ------------------------------------------------------------------

    /**
     * 构造 {@code query_brand_list} 的 {@code result}。
     *
     * <p>字段名与 {@code BrandItem} 的私有字段对应：{@code systemApp, brandCode, brandSwitch,
     * brandName, brandIcon, hostPkgName}。
     */
    static String brandListJson(ClassLoader cl) {
        final StringBuilder sb = new StringBuilder(2048).append('[');
        boolean first = true;

        for (App app : resolveApps(cl)) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('{')
                    .append("\"systemApp\":").append(app.systemService == 1)
                    .append(",\"brandCode\":\"").append(esc(app.brandCode)).append('"')
                    .append(",\"brandSwitch\":1")
                    .append(",\"brandName\":\"").append(esc(app.appName)).append('"')
                    .append(",\"brandIcon\":\"\"")
                    .append(",\"hostPkgName\":\"").append(esc(app.hostPkgName)).append('"')
                    .append('}');
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------
    // 反射读取 App 内置白名单
    // ------------------------------------------------------------------

    private static final class App {
        final String hostPkgName;
        final String appName;
        final String brandCode;
        final int systemService;

        App(String hostPkgName, String appName, String brandCode, int systemService) {
            this.hostPkgName = hostPkgName == null ? "" : hostPkgName;
            this.appName = appName == null ? "" : appName;
            this.brandCode = brandCode == null ? "" : brandCode;
            this.systemService = systemService;
        }
    }

    /**
     * 反射调用 {@code FluidConfigCenter.INSTANCE.defaultApps()}。
     * 失败时回退到内置保底清单，保证设置页至少能渲染。
     */
    private static List<App> resolveApps(ClassLoader cl) {
        try {
            final Class<?> cc = Class.forName(FLUID_CONFIG_CENTER, false, cl);

            final Field instance = cc.getField("INSTANCE");
            final Object self = instance.get(null);
            if (self == null) {
                throw new IllegalStateException("FluidConfigCenter.INSTANCE is null");
            }

            final Method defaultApps = cc.getDeclaredMethod("defaultApps");
            defaultApps.setAccessible(true);
            final Object raw = defaultApps.invoke(self);
            if (!(raw instanceof List)) {
                throw new IllegalStateException("defaultApps() did not return a List");
            }

            @SuppressWarnings("unchecked")
            final List<Object> list = (List<Object>) raw;
            final java.util.ArrayList<App> out = new java.util.ArrayList<>(list.size());
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                final Class<?> c = o.getClass();
                out.add(new App(
                        str(call(c, o, "getHostPkgName")),
                        str(call(c, o, "getAppName")),
                        str(call(c, o, "getBrandCode")),
                        num(call(c, o, "getSystemService"))));
            }
            Log.i(TAG, "FakeData: resolved " + out.size() + " apps from FluidConfigCenter.defaultApps()");
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "FakeData: defaultApps() unavailable (" + t.getClass().getSimpleName()
                    + "), using fallback list of " + FALLBACK.length);
            final java.util.ArrayList<App> out = new java.util.ArrayList<>(FALLBACK.length);
            for (String[] row : FALLBACK) {
                out.add(new App(row[0], row[1], row[2], Integer.parseInt(row[3])));
            }
            return Collections.unmodifiableList(out);
        }
    }

    private static Object call(Class<?> c, Object self, String name) {
        try {
            final Method m = c.getMethod(name);
            m.setAccessible(true);
            return m.invoke(self);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    // ------------------------------------------------------------------
    // JSON 转义
    // ------------------------------------------------------------------

    private static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }
}
