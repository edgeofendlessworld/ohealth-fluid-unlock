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
 * <p>因此本类的 {@code fluid_data} 必须<b>同时</b>满足两套字段契约：
 * 新协议的 {@code fluid_texts → KEY_TEXTS}，以及旧协议的扁平键
 * {@code B/B0/B1/C1/C2/C3/D19/D20/DEFAULT_C}（见 {@code ConverterWorker.oldPb}）。
 * 只喂其中一套时，走另一条分支的手表世代就会渲染出一张空白卡片。
 */
final class FluidInjector {

    private static final String TAG = "OHealthFluidUnlock";

    private static final String INJECTOR =
            "com.heytap.health.watch.notification.impl.fluid.external.ExternalFluidCloudInjector";
    private static final String TEXT_BEAN =
            "com.heytap.health.watch.notification.impl.fluid.TextBean";

    /** {@code normalizeFluidBundle} 未指定时使用的默认文案 key。 */
    private static final String DEFAULT_C = "DEFAULT_C";

    /**
     * 文本层级常量。
     *
     * <p>这些字符串会经 {@code TextBean} → protobuf {@code TextElement.setLevel()}
     * <b>原样下发到手表格件</b>，固件据此决定把文本放进哪个槽位。
     * 用错层级的后果是"岛存在但内容全空"。
     *
     * <p>取值来自 {@code CardDataBuilderHelper.extractAndValidateTitleDes}：
     * <ul>
     *   <li>标题：{@code B1}（备选 {@code externalTitle}）</li>
     *   <li>正文：{@code C1}</li>
     *   <li>副标题：{@code externalSubTitle} / {@code subTitle1} / {@code subTitle2}</li>
     * </ul>
     *
     * <p>注意：{@code D1} / {@code A1} 是<b>图片</b>层级（{@code pickLargeIcon} 用），
     * 不是文本层级，混用会导致文本不显示。
     */
    private static final String LEVEL_TITLE = "B1";
    private static final String LEVEL_CONTENT = "C1";
    private static final String LEVEL_SUBTITLE = "subTitle1";

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

        // fluid_texts：parseTextItems 从中读取并构造 TextBean，
        // 层级必须用文本语义名（B1 = 标题 / C1 = 正文），否则手表拿到空槽位。
        final ArrayList<Bundle> textItems = new ArrayList<>(3);

        // 标题
        if (title != null && !title.isEmpty()) {
            textItems.add(textItem(LEVEL_TITLE, title));
        }
        // 正文
        if (text != null && !text.isEmpty()) {
            textItems.add(textItem(LEVEL_CONTENT, text));
        }
        // 正文缺失时用标题兜底，避免整张卡片没有可显示内容
        if (textItems.isEmpty()) {
            textItems.add(textItem(LEVEL_CONTENT, pkg));
        }

        fluidData.putParcelableArrayList("fluid_texts", textItems);

        // ------------------------------------------------------------------
        // 关键：两套字段契约同时满足。
        //
        // ConverterWorker 有新旧两条填充路径，读的是**同一个 bundle**，但键完全不同：
        //   newPb(hnb, bundle, builder)  读 KEY_TEXTS / KEY_IMAGES（由 normalizeFluidBundle
        //                                从 fluid_texts 归一化出来，上面已经有了）
        //   oldPb(bundle, builder)       读**扁平字符串键**：
        //                                  B / B0 / B1  → setB / setB0 / setB1
        //                                  C1 / C2 / C3  → setC1 / setC2 / setC3
        //                                  *_COLOR       → 各层颜色
        //                                  D19 / D20     → 图片
        //                                且当 C1/C2/C3 全为空时，C1 回落 DEFAULT_C。
        //
        // 之前只喂了 fluid_texts：走 newPb 的世代没问题，走 oldPb 的世代就只剩
        // 一个空的 DEFAULT_C 回落值 —— 卡片是空白的。这里把扁平键也一起写进去。
        // ------------------------------------------------------------------
        final String titleSafe = title == null ? "" : title;
        final String textSafe = text == null ? "" : text;

        // DEFAULT_C 必须放在 fluid_data **里面**：normalizeFluidBundle 是从
        // fluidData 里 getString("DEFAULT_C", "流体云样例") 读的，
        // 放在外层 extras 上等于没生效（日志里一直回落成"流体云样例"）。
        fluidData.putString(DEFAULT_C, titleSafe);

        // 旧协议扁平键：B1 = 标题，C1 = 正文（与 extractAndValidateTitleDes 一致）
        if (!titleSafe.isEmpty()) {
            fluidData.putString("B1", titleSafe);
        }
        if (!textSafe.isEmpty()) {
            fluidData.putString("C1", textSafe);
        } else if (!titleSafe.isEmpty()) {
            // 正文缺失时用标题兜底，避免旧协议下 C1 落到 DEFAULT_C 变成一行重复字
            fluidData.putString("C1", titleSafe);
        }

        final Bundle extras = new Bundle();
        extras.putString("sbn_package", pkg);
        extras.putString("sbn_title", titleSafe);
        extras.putString("sbn_text", textSafe);
        extras.putInt("sbn_id", (int) (System.currentTimeMillis() & 0x7fffffff));
        extras.putBundle("fluid_data", fluidData);

        Log.i(TAG, "[INJECT] extras: pkg=" + pkg
                + ", texts=" + textItems.size()
                + " (" + LEVEL_TITLE + "=" + title + ", " + LEVEL_CONTENT + "=" + text + ")"
                + ", legacy B1/C1 + DEFAULT_C in fluid_data");

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
