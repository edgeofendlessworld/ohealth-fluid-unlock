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

    /**
     * 诊断模式：把「层级标签」撒进文本槽位，同一条通知发两张卡（两种模板各一张）。
     *
     * <p>手表拿到卡却不渲染时，靠猜字段效率极低。这里改成让卡自己回答：
     * 每个 {@code fluid_texts} 条目的 {@code text} 直接写成它自己的层级名
     * （{@code A1} 就写 "A1"）。模板真正会渲染的槽位，就会把对应字母显示出来 ——
     * 一行日志都不用看，眼前出现哪些字母就说明哪些层级被固件接受。
     *
     * <p>同时发两种模板，是为了区分「模板选错」和「层级写错」：
     * 两张卡里只要有一张出现字母，问题就只在模板；两张都空白，问题在层级或颜色。
     *
     * <p><b>已关闭</b>：两次实测（只撒层级名、再叠加白/红双配色与 compat/common 双模板）
     * 手表上都是两个全空白框，一个字母都没出现。这说明不是「槽位名猜错」，
     * 而是这一代手表根本不渲染这类 seeding 卡的内容区 —— 继续喷雾没有信息增益，
     * 改由能力体检（{@code [GATE] ... original=} 与 {@code abilityFull=}）判断设备是否支持。
     */
    private static final boolean SLOT_PROBE = false;

    /** 诊断模式下每种模板发一张：{@code center:compat}（镜像通知）与 {@code center:common}（通用卡）。 */
    private static final String[] PROBE_TYPES = {"center:compat", "center:common"};

    /**
     * 撒进槽位的层级标签：都是 {@code CardDataBuilderHelper} 里出现过的文本层级。
     *
     * <p>每条的内容写成 {@code "层级/颜色"}，并且用不同颜色发送。这样一次实验同时回答两个问题：
     * 模板吃哪一层（出现哪个层级名），以及颜色是否会踩到背景上（同名文字在哪种颜色下能看见）。
     */
    private static final String[] PROBE_LEVELS = {"A1", "B", "C2", "C3"};

    /** 与 {@link #PROBE_LEVELS} 一一对应的颜色：白（默认色）、深灰、红、绿。 */
    private static final int[] PROBE_COLORS = {
            -1,                // 0xFFFFFFFF，即 doInject 默认色
            0xFF333333,        // 深灰（刻意避开纯黑：纯黑会被 ensureVisibleTextColor 改写成默认色）
            0xFFFF0000,        // 红
            0xFF00FF00,        // 绿
    };

    /**
     * 两种配色方案：纯白与纯红，用来分辨「白字白底」这类不可见情况。
     *
     * <p>第二种没用纯黑，因为 {@code ProcessorBase} 的颜色兜底会把纯黑改写成默认色，
     * 那样两张卡又会变得一模一样，对照就白做了。
     */
    private static final int[] PROBE_PALETTES = {-1, 0xFFFF0000};

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
        if (!SLOT_PROBE) {
            return injectOne(cl, pkg, title, text, null, 0, -1);
        }
        // 诊断模式：每种模板各发一张，标题直接写模板名 + 配色，方便对着手表认卡
        boolean any = false;
        for (int i = 0; i < PROBE_TYPES.length; i++) {
            final int palette = PROBE_PALETTES[i % PROBE_PALETTES.length];
            any |= injectOne(cl, pkg, title, text, PROBE_TYPES[i], i * 7919, palette);
        }
        return any;
    }

    /**
     * 注入一张卡片。
     *
     * @param cl       目标进程 ClassLoader
     * @param pkg      数据源包名（用于白名单校验，必须是 App 内置白名单里的包名）
     * @param title    标题
     * @param text     正文
     * @param probeType 诊断用模板名；非 null 时标题会被替换成它，并撒入层级标签
     * @param idOffset 诊断用 sbn_id 偏移，避免同一毫秒的两张卡被去重
     * @param palette  诊断用文字颜色
     */
    private static boolean injectOne(ClassLoader cl, String pkg, String title, String text,
                                     String probeType, int idOffset, int palette) {
        try {
            final Bundle extras = buildExtras(cl, pkg, title, text, probeType, idOffset, palette);
            if (extras == null) {
                return false;
            }

            final Class<?> cls = Class.forName(INJECTOR, false, cl);
            final Object injector = cls.getField("INSTANCE").get(null);

            final Method doInject = cls.getDeclaredMethod("doInject", Bundle.class);
            doInject.setAccessible(true);
            doInject.invoke(injector, extras);

            Log.i(TAG, "[INJECT] doInject ok: pkg=" + pkg + ", title=" + title
                    + (probeType == null ? "" : ", probe=" + probeType));
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
    private static Bundle buildExtras(ClassLoader cl, String pkg, String title, String text,
                                      String probeType, int idOffset, int palette) {
        final Bundle fluidData = new Bundle();

        // 诊断模式下标题写模板名 + 配色（W=白字 / R=红字），正文写占位词，
        // 避免和真实导航文案混起来 —— 手表上看到哪一行，就知道是哪张卡、哪种配色。
        final String paletteTag = palette == -1 ? "/W白" : "/R红";
        final String effTitle = probeType != null ? probeType + paletteTag : (title == null ? "" : title);
        final String effText = probeType != null ? "spray" : (text == null ? "" : text);

        // fluid_texts：parseTextItems 从中读取并构造 TextBean，
        // 层级必须用文本语义名（B1 = 标题 / C1 = 正文），否则手表拿到空槽位。
        final ArrayList<Bundle> textItems = new ArrayList<>(3 + PROBE_LEVELS.length);

        // 标题
        if (!effTitle.isEmpty()) {
            textItems.add(textItem(LEVEL_TITLE, effTitle, palette));
        }
        // 正文
        if (!effText.isEmpty()) {
            textItems.add(textItem(LEVEL_CONTENT, effText, palette));
        }
        // 正文缺失时用标题兜底，避免整张卡片没有可显示内容
        if (textItems.isEmpty()) {
            textItems.add(textItem(LEVEL_CONTENT, pkg, palette));
        }
        // 诊断：把层级名和颜色名写进各槽位，看模板到底渲染哪一层、哪种颜色能被看见
        if (probeType != null) {
            for (int i = 0; i < PROBE_LEVELS.length; i++) {
                final int color = PROBE_COLORS[i % PROBE_COLORS.length];
                textItems.add(textItem(PROBE_LEVELS[i], PROBE_LEVELS[i] + colorName(color), color));
            }
        }

        fluidData.putParcelableArrayList("fluid_texts", textItems);

        // ------------------------------------------------------------------
        // 图片：原生卡片的 KEY_IMAGES 里**必定**同时有 D1（大图）与 A1（小图）两层。
        //
        // 依据 ProcessorSeedingCard.applyImageResult：模板里没有 D1 时，它会拿 A1 / A / A0
        // 兜底合成一张 D1；再没有就调 getDefaultImageBean("D1", …)，而那个方法取的是
        // FluidConfigCenter 白名单应用表里该 App 的图标（PNG、宽 120）。也就是说
        // **一张真实的卡片绝不会没有图**。我们之前一张图都没给，是结构上和原生差得最远的地方。
        //
        // ImageBean 的 md5 由 parseImageItems 自己按 bytes 算（也可以显式给 "md5" 键），
        // 新协议里 imageKey = md5 + "_" + level，图片字节本身由 convertFluid 里的
        // sendImages(bundle) 单独发一次，所以这里只要把 level + bytes 填对。
        // ------------------------------------------------------------------
        int imageCount = 0;
        final byte[] icon = appIconPng(pkg);
        if (icon != null) {
            final ArrayList<Bundle> imageItems = new ArrayList<>(2);
            imageItems.add(imageItem("D1", icon));
            imageItems.add(imageItem("A1", icon));
            fluidData.putParcelableArrayList("fluid_images", imageItems);
            imageCount = imageItems.size();
        }

        // KEY_TYPES：原生卡片由 ProcessorSeedingCard 从云端模板节点算出（形如 center:compat），
        // 缺这个键时 ConverterWorker.newPb 会落到 center:common。这里显式给镜像通知用的模板。
        fluidData.putString("KEY_TYPES", "center:compat");

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
        final String titleSafe = effTitle;
        final String textSafe = effText;

        // 诊断模式的模板覆盖：normalizeFluidBundle 会整包拷贝 fluid_data，
        // 这个自定义键能一直活到 newPb 的 bundle 参数里，探针据此改写 type。
        if (probeType != null) {
            fluidData.putString("probe_type_override", probeType);
        }

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

        // 诊断：oldPb 分支能读的扁平键也补上同名内容，排除分支差异的干扰
        if (probeType != null) {
            fluidData.putString("B", "B");
            fluidData.putString("C2", "C2");
            fluidData.putString("C3", "C3");
        }

        final Bundle extras = new Bundle();
        extras.putString("sbn_package", pkg);
        extras.putString("sbn_title", titleSafe);
        extras.putString("sbn_text", textSafe);
        extras.putInt("sbn_id", (int) (System.currentTimeMillis() & 0x7fffffff) + idOffset);
        extras.putBundle("fluid_data", fluidData);

        Log.i(TAG, "[INJECT] extras: pkg=" + pkg
                + ", texts=" + textItems.size()
                + " (" + LEVEL_TITLE + "=" + effTitle + ", " + LEVEL_CONTENT + "=" + effText + ")"
                + ", images=" + imageCount + (imageCount == 0 ? " (缺少图标!)" : " (D1+A1)")
                + ", KEY_TYPES=center:compat, legacy B1/C1 + DEFAULT_C in fluid_data"
                + (probeType == null ? "" : ", probe_type_override=" + probeType + ", palette=" + paletteTag));

        return extras;
    }

    /** 单个 {@code fluid_texts} 元素；键名与 {@code parseTextItems} 的读取保持一致。 */
    private static Bundle textItem(String level, String content) {
        return textItem(level, content, -1);
    }

    /** 同上，可指定颜色；{@code -1} 即 {@code 0xFFFFFFFF}。 */
    private static Bundle textItem(String level, String content, int color) {
        final Bundle item = new Bundle();
        item.putString("level", level);
        item.putString("text", content == null ? "" : content);
        item.putInt("color", color);
        item.putInt("bg_color", 0);      // 透明
        return item;
    }

    /**
     * 单个 {@code fluid_images} 元素；键名与 {@code parseImageItems} 的读取保持一致
     * （{@code level} / {@code bytes} / {@code square}，md5 由那边按字节算）。
     */
    private static Bundle imageItem(String level, byte[] bytes) {
        final Bundle item = new Bundle();
        item.putString("level", level);
        item.putByteArray("bytes", bytes);
        item.putBoolean("square", false);
        return item;
    }

    /**
     * 取数据源 App 的图标，压成 PNG。
     *
     * <p>对应原生 {@code ProcessorBase.getDefaultImageBean} 的兜底逻辑：真实卡片缺图时
     * 会用白名单应用表里的图标补一张（PNG、宽 120）。我们直接取系统图标，效果等价。
     */
    private static byte[] appIconPng(String pkg) {
        try {
            final android.content.Context ctx = appContext();
            if (ctx == null) {
                return null;
            }
            final android.graphics.drawable.Drawable d =
                    ctx.getPackageManager().getApplicationIcon(pkg);
            android.graphics.Bitmap bmp;
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                bmp = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            } else {
                final int w = Math.max(1, d.getIntrinsicWidth());
                final int h = Math.max(1, d.getIntrinsicHeight());
                bmp = android.graphics.Bitmap.createBitmap(w, h,
                        android.graphics.Bitmap.Config.ARGB_8888);
                final android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                d.setBounds(0, 0, w, h);
                d.draw(canvas);
            }
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "[INJECT] icon failed for " + pkg + ": " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 当前进程的 Application；模组运行在 App 进程里，可直接取到。 */
    private static android.content.Context appContext() {
        try {
            final Class<?> at = Class.forName("android.app.ActivityThread");
            final Object thread = at.getMethod("currentActivityThread").invoke(null);
            final Object app = thread == null ? null : at.getMethod("getApplication").invoke(thread);
            return app instanceof android.content.Context ? (android.content.Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 颜色名，用于写进诊断文本，让手表上的字自己说出它是什么颜色。 */
    private static String colorName(int color) {
        switch (color) {
            case -1:
                return "/白";
            case 0xFF333333:
                return "/深灰";
            case 0xFFFF0000:
                return "/红";
            case 0xFF00FF00:
                return "/绿";
            default:
                return "/" + Integer.toHexString(color);
        }
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
