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
     * 桌面「流体指示窗」的文本槽位。
     *
     * <p>{@code ConverterWorker.extractIndicatorValue} 只认这五个槽位：
     * 图片 {@code A1} / {@code A} / {@code A0}，文本 {@code B} / {@code B0}。
     * 它们拼成的字符串一变，原型上的 {@code updateIndicator} 才会从 1 变 2（刷新桌面指示窗）。
     */
    private static final String LEVEL_INDICATOR = "B";

    /**
     * 自用标记：期望的 {@code KEY_NOTIFY_LEVEL}（0 = 正常/不震，1 = 重要/震）。
     *
     * <p>{@code normalizeFluidBundle} 会无条件把 {@code KEY_NOTIFY_LEVEL} 写成 1，
     * 所以只能随 {@code fluid_data} 带一个自用键过去，由回包 hook 再写回真实值。
     */
    static final String NOTIFY_LEVEL_KEY = "ohealth_notify_level";

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
    static boolean inject(ClassLoader cl, String pkg, String title, String text, int smallIconRes,
                          int sbnId, int notifyLevel) {
        if (!SLOT_PROBE) {
            return injectOne(cl, pkg, title, text, null, 0, -1, sbnId, notifyLevel);
        }
        // 诊断模式：每种模板各发一张，标题直接写模板名 + 配色，方便对着手表认卡
        boolean any = false;
        for (int i = 0; i < PROBE_TYPES.length; i++) {
            final int palette = PROBE_PALETTES[i % PROBE_PALETTES.length];
            any |= injectOne(cl, pkg, title, text, PROBE_TYPES[i], i * 7919, palette, sbnId + i,
                    notifyLevel);
        }
        return any;
    }

    /**
     * 让手表撤掉一张卡片（对应 {@code ExternalFluidCloudInjector.doRemove(Bundle)}）。
     *
     * <p>原生路径在通知被移除时会走 {@code HealthNotificationRegisterCenter.onNotificationFluidRemoved}，
     * 我们只 hook 了 {@code onNotificationPosted}，所以以前源通知消失后手表上的卡会变成僵尸。
     *
     * <p>注意 {@code doRemove} 只认 {@code sbn_package} + {@code sbn_id} 两个键，
     * 且 {@code sbn_id} 必须与注入时<b>完全一致</b>（它构成卡片的 key），
     * 否则删不掉任何东西。
     */
    static boolean remove(ClassLoader cl, String pkg, int sbnId) {
        try {
            final Class<?> cls = Class.forName(INJECTOR, false, cl);
            final Object injector = cls.getField("INSTANCE").get(null);
            final Method doRemove = cls.getDeclaredMethod("doRemove", Bundle.class);
            doRemove.setAccessible(true);

            final Bundle extras = new Bundle();
            extras.putString("sbn_package", pkg);
            extras.putInt("sbn_id", sbnId);
            doRemove.invoke(injector, extras);

            Log.i(TAG, "[INJECT] doRemove ok: pkg=" + pkg + ", id=" + sbnId);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "[INJECT] remove failed: pkg=" + pkg + ", id=" + sbnId, t);
            return false;
        }
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
                                     String probeType, int idOffset, int palette, int sbnId,
                                     int notifyLevel) {
        try {
            final Bundle extras = buildExtras(cl, pkg, title, text, probeType, idOffset, palette,
                    sbnId, notifyLevel);
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
                                      String probeType, int idOffset, int palette, int sbnId,
                                      int notifyLevel) {
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

        // ------------------------------------------------------------------
        // 桌面「流体指示窗」（表盘上那条显示距离/方位的指示器）
        //
        // 依据 ConverterWorker.extractIndicatorValue：指示窗的内容**只**由五个槽位决定——
        //     图片 A1 / A / A0   +   文本 B / B0
        // 而且 isDuplicateFluidSeedingCard 拿这五个槽位拼成的字符串做对比：
        //     isIndicatorElementChanged(...) 为 true → 给 proto 打 updateIndicator = 2
        //     （手表据此刷新桌面指示窗）；否则恒为 1（只换卡片内容，不动指示窗）。
        // 我们以前一个 B/B0 文本都没给，五个槽位全空 → 指示窗永远不刷新。
        // 这里补上 B = 距离文案，A1 = 方向箭头（见下面的 fluid_images）。
        // ------------------------------------------------------------------
        final String indicator = probeType == null ? indicatorText(effTitle, effText) : "";
        if (!indicator.isEmpty()) {
            textItems.add(textItem(LEVEL_INDICATOR, indicator, palette));
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
        // A1 同时也是桌面指示窗里的「方位」图标，这里放方向箭头而不是 App 图标
        final byte[] arrow = probeType == null
                ? turnArrowPng(effTitle + " " + effText, distanceText(effTitle, effText))
                : null;
        if (icon != null || arrow != null) {
            final ArrayList<Bundle> imageItems = new ArrayList<>(2);
            // D1：卡片里的大图，原生取自数据源 App 图标
            imageItems.add(imageItem("D1", icon != null ? icon : arrow));
            // A1：小图 / 指示窗箭头
            imageItems.add(imageItem("A1", arrow != null ? arrow : icon));
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

        // 震动档位：normalizeFluidBundle 会**无条件**把 KEY_NOTIFY_LEVEL 覆盖成 1
        // （= NOTIFY_IMPORTANT），于是每一次导航更新手表都震一下。
        // 真正想要的档位放进 fluid_data 带过去，由 FluidUnlockModule 里
        // normalizeFluidBundle 的回包 hook 再写回去。
        fluidData.putInt(NOTIFY_LEVEL_KEY, notifyLevel);

        // 档位改成「正常」后，卡片仍然要显示出来：commonPb 会读这个键并给
        // 原型打上 forceShow。不加的话，静默更新有可能连卡都不弹。
        fluidData.putBoolean("KEY_NOTIFY_FORCE_SHOW", true);

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
        } else if (!indicator.isEmpty()) {
            // 走 oldPb 的世代里 B 就是顶部文本，指示窗也从这里取距离
            fluidData.putString("B", indicator);
        }

        final Bundle extras = new Bundle();
        extras.putString("sbn_package", pkg);
        extras.putString("sbn_title", titleSafe);
        extras.putString("sbn_text", textSafe);
        // sbn_id 是这张卡在手表上的身份（HealthNotificationBean.key = pkg|id|tag|uid）。
        // 必须沿用源通知的 id：这样同一条通知的更新才是「替换同一张卡」。
        // 以前用 currentTimeMillis 当 id，每次更新都生成新卡 —— 手表上表现为
        // 「内容永远停在第一版」并且「关掉之后下一帧又冒出来」。
        extras.putInt("sbn_id", sbnId + idOffset);
        extras.putBundle("fluid_data", fluidData);

        Log.i(TAG, "[INJECT] extras: pkg=" + pkg + ", id=" + (sbnId + idOffset)
                + ", texts=" + textItems.size()
                + " (" + LEVEL_TITLE + "=" + effTitle + ", " + LEVEL_CONTENT + "=" + effText
                + (indicator.isEmpty() ? "" : ", " + LEVEL_INDICATOR + "=" + indicator) + ")"
                + ", images=" + imageCount + (imageCount == 0 ? " (缺少图标!)" : " (D1+A1 箭头)")
                + ", dir=" + turnLabel(effTitle + " " + effText)
                + "@" + turnAngle(effTitle + " " + effText) + "°"
                + ", KEY_TYPES=center:compat, notifyLevel=" + notifyLevel
                + ", legacy B1/C1 + DEFAULT_C in fluid_data"
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
     * 抠出桌面指示窗要显示的文本：优先「距离」（如 {@code 300米}），没有距离就退化成方向词
     * （如 {@code 左转}）。
     *
     * <p>指示窗只有一个文本槽位（{@code B}），放距离比放整句话更接近原生：
     * 原生导航卡的 B 层就是剩余距离，方位交给 A1/A 的箭头图表达。
     * 很多导航通知只在文案里偶尔出现距离，所以这里保留方向词兜底 —— 至少让指示窗
     * 随转向变化，而不是永远一片空白。
     */
    private static String indicatorText(String title, String text) {
        final String distance = distanceText(title, text);
        if (!distance.isEmpty()) {
            return distance;
        }
        final String all = (title == null ? "" : title) + " " + (text == null ? "" : text);
        return turnLabel(all);
    }

    /**
     * 从文案里抠出距离（{@code 9米} / {@code 1.2公里}），单位统一成「米 / 公里」。
     * 抠不到返回空串。
     */
    private static String distanceText(String title, String text) {
        final String all = (title == null ? "" : title) + " " + (text == null ? "" : text);
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*(公里|千米|米|km|KM|m)")
                .matcher(all);
        if (!m.find()) {
            return "";
        }
        String unit = m.group(2);
        if ("km".equalsIgnoreCase(unit) || "千米".equals(unit)) {
            unit = "公里";
        } else if ("m".equals(unit)) {
            unit = "米";
        }
        return m.group(1) + unit;
    }

    /**
     * 从文案里找方向：先找转向动作（左转/右转…），再找**方位词**（向东北 / 往南 / 正西），
     * 最后才判断直行。找不到返回空串。
     *
     * <p>方位词必须带「向 / 往 / 朝 / 正」前缀或「方向」后缀才认 ——
     * 否则「山东路」「东直门」这种路名会把箭头带跑偏。
     */
    private static String turnLabel(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 1) 转向动作（长的先匹配，避免「左前方」被「左转」抢走）
        final String[] turns = {
                "左前方", "右前方", "左后方", "右后方",
                "左前", "右前", "左后", "右后",
                "左转", "右转", "掉头", "调头",
                "靠左", "靠右", "向左", "向右", "左拐", "右拐",
        };
        for (String turn : turns) {
            if (text.contains(turn)) {
                return turn;
            }
        }
        // 2) 方位（八向）
        final String[] dirs = {"东北", "东南", "西北", "西南", "东", "南", "西", "北"};
        for (String dir : dirs) {
            if (text.contains("向" + dir) || text.contains("往" + dir)
                    || text.contains("朝" + dir) || text.contains("正" + dir)
                    || text.contains(dir + "方向")) {
                return dir;
            }
        }
        // 3) 直行
        final String[] straight = {"直行", "直走", "前行", "继续", "沿"};
        for (String s : straight) {
            if (text.contains(s)) {
                return s;
            }
        }
        return "";
    }

    /** 方向词 → 箭头旋转角度（0 = 向上，正数顺时针）。 */
    private static int turnAngle(String text) {
        switch (turnLabel(text)) {
            case "左前方":
            case "左前":
                return -45;
            case "右前方":
            case "右前":
                return 45;
            case "左后方":
            case "左后":
                return -135;
            case "右后方":
            case "右后":
                return 135;
            case "左转":
            case "向左":
            case "左拐":
            case "靠左":
                return -90;
            case "右转":
            case "向右":
            case "右拐":
            case "靠右":
                return 90;
            case "掉头":
            case "调头":
                return 180;
            case "东北":
                return 45;
            case "东南":
                return 135;
            case "西南":
                return 225;
            case "西北":
                return 315;
            case "东":
                return 90;
            case "南":
                return 180;
            case "西":
                return 270;
            default:
                return 0;   // 北 / 直行 / 未命中
        }
    }

    /**
     * 「关键节点」判定用的指纹：方向变了才算进入下一个转向节点。
     *
     * <p>{@code NotificationBridge} 用它决定要不要让手表震一下——
     * 导航中每更新一次距离/剩余时间都很频繁，只有转向指令变化才是该提醒的节点。
     */
    static int maneuverKey(String title, String text) {
        return turnAngle((title == null ? "" : title) + " " + (text == null ? "" : text));
    }

    /**
     * 画一张方向箭头 PNG，给桌面指示窗当「方位」图标（{@code A1} 层）。
     *
     * <p>原生导航卡的箭头来自云端模板（webp 资源），我们拿不到，所以按通知里的
     * 方向词自己画一个：直行 / 左转 / 右转 / 左前右前 / 掉头。
     * 尺寸取 120，与 {@code getDefaultImageBean} 里原生的图标宽度一致。
     *
     * <p>有距离时把距离一起画进图里：手表桌面指示窗只认 {@code A1/A/A0} 图 +
     * {@code B/B0} 文本，两个槽位分别渲染会变成「箭头 / 距离」交替；
     * 合成一张图至少保证画面里箭头和距离同时在场。
     */
    private static byte[] turnArrowPng(String text, String distance) {
        try {
            final int size = 120;
            final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            final android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            canvas.drawColor(0x00000000);

            final boolean withText = distance != null && !distance.isEmpty();
            // 带文字时把箭头压到上半部分，底部留给距离
            final float headTop = withText ? size * 0.05f : size * 0.12f;
            final float headBase = withText ? size * 0.34f : size * 0.44f;
            final float shaftBottom = withText ? size * 0.58f : size * 0.85f;
            final float headHalf = withText ? 22f : 26f;
            final float shaftHalf = withText ? 7f : 9f;
            final float pivotY = withText ? size * 0.31f : size / 2f;

            canvas.save();
            canvas.rotate(turnAngle(text), size / 2f, pivotY);

            final android.graphics.Paint paint = new android.graphics.Paint();
            paint.setAntiAlias(true);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(android.graphics.Paint.Style.FILL);

            final float cx = size / 2f;
            // 箭头杆
            canvas.drawRect(cx - shaftHalf, headBase, cx + shaftHalf, shaftBottom, paint);
            // 箭头头
            final android.graphics.Path head = new android.graphics.Path();
            head.moveTo(cx, headTop);
            head.lineTo(cx + headHalf, headBase);
            head.lineTo(cx - headHalf, headBase);
            head.close();
            canvas.drawPath(head, paint);
            canvas.restore();

            if (withText) {
                final android.graphics.Paint textPaint = new android.graphics.Paint();
                textPaint.setAntiAlias(true);
                textPaint.setColor(0xFFFFFFFF);
                textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
                textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                float textSize = 34f;
                textPaint.setTextSize(textSize);
                while (textSize > 12f && textPaint.measureText(distance) > size * 0.94f) {
                    textSize -= 2f;
                    textPaint.setTextSize(textSize);
                }
                canvas.drawText(distance, size / 2f, size * 0.94f, textPaint);
            }

            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "[INJECT] arrow failed: " + t.getClass().getSimpleName());
            return null;
        }
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
     * <p>不同 App 把「转向提示 / 当前道路 / 方向」放在完全不同的键里：
     * 百度地图的步行导航经常只有 {@code EXTRA_TITLE} 和一堆 {@code EXTRA_SUB_TEXT} /
     * {@code EXTRA_INFO_TEXT} / ticker，高德则爱用 {@code EXTRA_BIG_TEXT}。
     * 所以这里把<b>所有</b>承载文案的字段都收进来，按出现顺序去重拼成正文 ——
     * 方向词和距离数字就藏在这些字段里，漏一个就表现为「箭头永远直行」。
     */
    static String[] extractText(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return new String[]{"", ""};
        }
        final Notification n = sbn.getNotification();
        final Bundle extras = n.extras;

        String title = "";
        final StringBuilder joined = new StringBuilder();

        if (extras != null) {
            title = str(extras.getCharSequence(Notification.EXTRA_TITLE));
            if (title.isEmpty()) {
                title = str(extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
            }
            appendUnique(joined, extras.getCharSequence(Notification.EXTRA_TEXT));
            appendUnique(joined, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            appendUnique(joined, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            appendUnique(joined, extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
            appendUnique(joined, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        }
        appendUnique(joined, n.tickerText);
        return new String[]{title, joined.toString()};
    }

    /**
     * 把通知里所有「字符串类」extras 键值打出来，用于确认 App 到底把转向/方位放在哪个键。
     *
     * <p>只在日志里用，限制条数与长度，免得刷屏。
     */
    static String describeRawExtras(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return "";
        }
        final Bundle extras = sbn.getNotification().extras;
        if (extras == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String key : extras.keySet()) {
            if (key == null || key.startsWith("android.") || key.startsWith("com.android.")) {
                continue;   // 系统自带的 action/进度条之类的噪声
            }
            final Object v = extras.get(key);
            if (!(v instanceof CharSequence) && !(v instanceof String[])) {
                continue;
            }
            if (n++ >= 12) {
                sb.append(" ...");
                break;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String value = v instanceof String[] ? String.join("/", (String[]) v) : str(v);
            if (value.length() > 60) {
                value = value.substring(0, 60) + "…";
            }
            sb.append(key).append('=').append(value);
        }
        return sb.toString();
    }

    private static void appendUnique(StringBuilder sb, CharSequence cs) {
        final String value = str(cs).trim();
        if (value.isEmpty() || sb.indexOf(value) >= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("  ");
        }
        sb.append(value);
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
