package io.github.endlessworld.ohealthfluid;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 把第三方应用的通知转成流体云卡片，直接喂给 OPPO 健康的外部注入通道。
 *
 * <p>走的入口是 App 自带的纯 Bundle 通道：
 *
 * <pre>
 * ExternalFluidCloudInjector.doInject(Bundle)
 *   ├─ 读 sbn_package / sbn_title / sbn_text / sbn_id / fluid_data
 *   ├─ normalizeFluidBundle()   ← 自动补 KEY_DATA_SOURCE_PKG / DEFAULT_C /
 *   │                             KEY_NOTIFY_LEVEL / KEY_FLUID_DISMISS_ONCE
 *   ├─ isFluidTypeSupported()   ← KEY_TYPES 为空时无条件放行
 *   ├─ pickLargeIcon()          ← 从 KEY_IMAGES 找 D1 / A1 层级的图
 *   ├─ 构造 Notification + StatusBarNotification
 *   └─ HealthNotificationRegisterCenter.onNotificationFluid(hnb, bundle)  → 推手表
 * </pre>
 *
 * <p>因此本类只需要提供通知的基本字段与 {@code fluid_texts}，
 * 其余由 {@code normalizeFluidBundle} 兜底。
 */
final class FluidInjector {

    private static final String TAG = "OHealthFluidUnlock";

    private static final String INJECTOR =
            "com.heytap.health.watch.notification.impl.fluid.external.ExternalFluidCloudInjector";
    private static final String TEXT_BEAN =
            "com.heytap.health.watch.notification.impl.fluid.TextBean";

    /** {@code normalizeFluidBundle} 未指定时使用的默认文案 key。 */
    private static final String DEFAULT_C = "DEFAULT_C";

    private FluidInjector() {
    }

    /**
     * 注入一张卡片。
     *
     * @param cl       目标进程 ClassLoader
     * @param pkg      数据源包名（用于白名单校验，必须是 App 内置白名单里的包名）
     * @param title    标题
     * @param text     正文
     * @param smallIconRes 小图标资源 id（可用 0，缺省时用 App 自身默认）
     * @return 是否成功调用到 doInject
     */
    static boolean inject(ClassLoader cl, String pkg, String title, String text, int smallIconRes) {
        try {
            final Bundle extras = buildExtras(cl, pkg, title, text);
            if (extras == null) {
                return false;
            }

            final Class<?> cls = Class.forName(INJECTOR, false, cl);
            final Object injector = cls.getField("INSTANCE").get(null);

            final Method doInject = cls.getDeclaredMethod("doInject", Bundle.class);
            doInject.setAccessible(true);
            doInject.invoke(injector, extras);

            Log.i(TAG, "[INJECT] doInject ok: pkg=" + pkg + ", title=" + title);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "[INJECT] failed: pkg=" + pkg, t);
            return false;
        }
    }

    /**
     * 构造 {@code doInject} 需要的 extras。
     *
     * <p>结构（键名已与 APK 中 {@code doInject} / {@code parseTextItems} 对齐）：
     * <pre>
     * {
     *   "sbn_package": "...", "sbn_title": "...", "sbn_text": "...",
     *   "sbn_id": 12345,
     *   "fluid_data": {                      // 子 Bundle，作为 fluid_data 传入
     *       "fluid_texts": [ Bundle{level, text, color, bg_color}, ... ]
     *   }
     * }
     * </pre>
     */
    private static Bundle buildExtras(ClassLoader cl, String pkg, String title, String text) {
        final Bundle fluidData = new Bundle();

        // fluid_texts：parseTextItems 从中读取并构造 TextBean
        final ArrayList<Bundle> textItems = new ArrayList<>(2);

        // 第一条：正文（D1 层级 = 主内容）
        textItems.add(textItem("D1", text));
        // 第二条：标题（A1 层级 = 副标题位）
        textItems.add(textItem("A1", title));
        fluidData.putParcelableArrayList("fluid_texts", textItems);

        final Bundle extras = new Bundle();
        extras.putString("sbn_package", pkg);
        extras.putString("sbn_title", title == null ? "" : title);
        extras.putString("sbn_text", text == null ? "" : text);
        extras.putInt("sbn_id", (int) (System.currentTimeMillis() & 0x7fffffff));
        extras.putBundle("fluid_data", fluidData);

        // 默认文案（normalizeFluidBundle 读取，缺失时回落"流体云样例"）
        extras.putString(DEFAULT_C, title == null ? "" : title);

        return extras;
    }

    /** 单个 {@code fluid_texts} 元素；键名与 {@code parseTextItems} 的读取保持一致。 */
    private static Bundle textItem(String level, String content) {
        final Bundle item = new Bundle();
        item.putString("level", level);
        item.putString("text", content == null ? "" : content);
        item.putInt("color", -1);        // 默认色（-1 = 0xFFFFFFFF）
        item.putInt("bg_color", 0);      // 透明
        return item;
    }

    // ------------------------------------------------------------------
    // 通知 → 卡片字段的提取
    // ------------------------------------------------------------------

    /**
     * 从 {@link StatusBarNotification} 提取标题与正文。
     *
     * <p>优先读 {@code Notification.EXTRA_TITLE} / {@code EXTRA_TEXT}，
     * 回落到 {@code EXTRA_TITLE_BIG} / {@code EXTRA_SUB_TEXT}。
     */
    static String[] extractText(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return new String[]{"", ""};
        }
        final Notification n = sbn.getNotification();
        final Bundle extras = n.extras;
        if (extras == null) {
            return new String[]{"", ""};
        }

        String title = str(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(extras.getCharSequence(Notification.EXTRA_TEXT));

        if (title.isEmpty()) {
            title = str(extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
        }
        if (text.isEmpty()) {
            text = str(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        }
        if (text.isEmpty()) {
            text = str(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        }
        if (text.isEmpty()) {
            text = str(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        }
        return new String[]{title, text};
    }

    /**
     * 判断某个包名能否被流体云接受：必须命中 OPPO 健康内置白名单
     * （{@code FluidConfigCenter.SUPPORT_APPS} 的 key 是 hostPkgName）。
     */
    static boolean isWhitelisted(ClassLoader cl, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        try {
            final Class<?> cc = Class.forName(
                    "com.heytap.health.watch.notification.impl.fluid.FluidConfigCenter",
                    false, cl);
            final Object self = cc.getField("INSTANCE").get(null);
            final Method m = cc.getDeclaredMethod("getSupportApp", String.class);
            m.setAccessible(true);
            final Object info = m.invoke(self, pkg);
            return info != null;
        } catch (Throwable t) {
            Log.w(TAG, "isWhitelisted check failed for " + pkg + ": "
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
