package cn.aqcraft.iusse.dialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.config.Category;
import cn.aqcraft.iusse.config.LangConfig;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.config.Priority;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;

/**
 * 反馈对话框。
 * <p>
 * 使用 Paper 原生 Dialog API（1.21.7 引入，签名在 1.21.7 ~ 26.x 之间保持兼容），
 * 因此不需要任何版本适配代码，也不需要自己画 GUI。
 *
 * <h2>两步式流程</h2>
 * 第一版是「一个下拉框 + 标题 + 正文」全塞在同一页里，功能上没问题，但观感很糙。
 * 现在默认走两步：
 * <ol>
 *   <li><b>选分类</b>：每种反馈是一个按钮，鼠标悬停显示该分类的说明
 *       （{@code multiAction} 网格，比下拉框少两次点击，也更容易看懂）</li>
 *   <li><b>填内容</b>：标题 + 详细内容，底部是「提交 / 返回」，返回可回到第一步改分类</li>
 * </ol>
 * 分类超过 {@link #MAX_PICKER_BUTTONS} 个时按钮网格会挤成一团，此时自动退回单页式
 * （下拉框 + 表单）；也可以在配置里用 {@code dialog.two-step: false} 强制单页。
 *
 * <p>两个对话框都可以在正文区带一个物品图标，用 {@code dialog.show-icon} 控制。
 * 刻意没有去设置输入框与正文的像素宽度 —— 那些字段各版本取值范围未必一致，
 * 而默认值（200 / 16）在原版各处都用得好好的。
 */
public class FeedbackDialog {

    /** 对话框输入项的键名，同时也是回调里读取值的键。 */
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_TITLE = "title";
    private static final String KEY_BODY = "body";
    private static final String KEY_PRIORITY = "priority";

    /** 原版按钮的像素宽度，100 与原版「确认 / 取消」按钮一致。 */
    private static final int BUTTON_WIDTH = 100;

    /** 分类按钮超过这个数量就不再用网格，改用下拉框，免得挤成一团。 */
    private static final int MAX_PICKER_BUTTONS = 8;

    /** 原版多按钮网格最多三列。 */
    private static final int MAX_COLUMNS = 3;

    private final AqIssuePlugin plugin;

    public FeedbackDialog(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 向玩家弹出反馈对话框（入口）。
     * <p>
     * 走进两步式还是单页式由配置与分类数量决定。
     */
    public void open(Player player) {
        List<Category> categories = plugin.getPluginConfig().getCategories();
        if (plugin.getPluginConfig().isTwoStepDialog() && categories.size() <= MAX_PICKER_BUTTONS) {
            openPicker(player);
        } else {
            openForm(player, null);
        }
    }

    // ------------------------------------------------------------------
    // 第一步：选分类
    // ------------------------------------------------------------------

    /** 分类选择页：每个分类一个按钮，外加一个「取消」。 */
    public void openPicker(final Player player) {
        final PluginConfig config = plugin.getPluginConfig();
        final LangConfig lang = plugin.getLang();
        final List<Category> categories = config.getCategories();

        List<ActionButton> buttons = new ArrayList<ActionButton>(categories.size());
        for (Category category : categories) {
            final Category target = category;
            buttons.add(ActionButton.builder(Component.text(plain(target.getName())))
                    .tooltip(Component.text(lang.plain("dialog.category-tooltip",
                            "name", target.getName(),
                            "description", target.getDescription()).trim()))
                    .action(DialogAction.customClick(
                            (view, audience) -> openLater(player, target),
                            ClickCallback.Options.builder().build()))
                    .build());
        }

        ActionButton exit = ActionButton.builder(Component.text(lang.plain("dialog.picker-cancel")))
                .tooltip(Component.text(lang.plain("dialog.picker-cancel-tooltip")))
                .width(BUTTON_WIDTH)
                .build();

        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder entry = factory.empty();
            entry.base(DialogBase.builder(Component.text(lang.plain("dialog.picker-title")))
                    .canCloseWithEscape(true)
                    .body(headerBody(config, lang, "dialog.picker-body", "dialog.picker-hint"))
                    .build());
            entry.type(DialogType.multiAction(buttons, exit, columnsFor(categories.size())));
        });

        player.showDialog(dialog);
    }

    /** 分类按钮列数：≤3 个排成一行，再多就固定两列。 */
    private static int columnsFor(int count) {
        return count <= MAX_COLUMNS ? Math.max(1, count) : 2;
    }

    // ------------------------------------------------------------------
    // 第二步：填内容
    // ------------------------------------------------------------------

    /**
     * 填写页：标题与详细内容。
     *
     * @param preset 上一步选好的分类；为 {@code null} 时表单里带一个分类下拉框（单页模式）
     */
    public void openForm(final Player player, final Category preset) {
        final PluginConfig config = plugin.getPluginConfig();
        final LangConfig lang = plugin.getLang();
        final List<Category> categories = config.getCategories();
        final Category initial = preset != null ? preset : categories.get(0);

        List<DialogInput> inputs = new ArrayList<DialogInput>(3);
        if (preset == null) {
            inputs.add(DialogInput.singleOption(KEY_CATEGORY,
                    Component.text(lang.plain("dialog.category-label")),
                    buildCategoryOptions(config, lang)).build());
        }
        inputs.add(DialogInput.text(KEY_TITLE, Component.text(lang.plain("dialog.title-label")))
                .maxLength(config.getMaxTitleLength())
                .build());
        inputs.add(DialogInput.text(KEY_BODY, Component.text(lang.plain("dialog.body-label")))
                .maxLength(config.getMaxBodyLength())
                .multiline(TextDialogInput.MultilineOptions.create(Integer.valueOf(6), Integer.valueOf(200)))
                .build());
        // 放在最后：一个单选比两个输入框快，安排在提交按钮上面顺手
        inputs.add(DialogInput.singleOption(KEY_PRIORITY,
                Component.text(lang.plain("dialog.priority-label")),
                buildPriorityOptions(config, lang)).build());

        final String formTitle = preset == null
                ? lang.plain("dialog.title")
                : lang.plain("dialog.form-title", "name", initial.getName());

        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder entry = factory.empty();
            entry.base(DialogBase.builder(Component.text(formTitle))
                    .canCloseWithEscape(true)
                    .body(headerBody(config, lang, "dialog.form-body", "dialog.body-hint",
                            "name", initial.getName(),
                            "description", initial.getDescription()))
                    .inputs(inputs)
                    .build());

            entry.type(DialogType.confirmation(
                    ActionButton.builder(Component.text(lang.plain("dialog.confirm")))
                            .tooltip(Component.text(lang.plain("dialog.confirm-tooltip")))
                            .width(BUTTON_WIDTH)
                            .action(DialogAction.customClick(
                                    (view, audience) -> onSubmit(player, view, initial, preset == null),
                                    ClickCallback.Options.builder().build()))
                            .build(),
                    backButton(player, preset)));
        });

        player.showDialog(dialog);
    }

    /**
     * 填写页的第二个按钮。
     * <p>
     * 两步式下是「返回」（回分类选择），单页式下是「取消」（没地方可返回，直接关掉）。
     */
    private ActionButton backButton(final Player player, Category preset) {
        LangConfig lang = plugin.getLang();
        if (preset == null) {
            return ActionButton.builder(Component.text(lang.plain("dialog.cancel")))
                    .tooltip(Component.text(lang.plain("dialog.cancel-tooltip")))
                    .width(BUTTON_WIDTH)
                    .build();
        }
        return ActionButton.builder(Component.text(lang.plain("dialog.back")))
                .tooltip(Component.text(lang.plain("dialog.back-tooltip")))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick(
                        (view, audience) -> openPickerLater(player),
                        ClickCallback.Options.builder().build()))
                .build();
    }

    /**
     * 「提交」按钮回调。
     *
     * @param fallback 表单里是否带了分类下拉框（单页模式）；带了就从表单读，
     *                 否则用上一步选好的分类
     */
    private void onSubmit(Player player, DialogResponseView view, Category preset, boolean fallback) {
        if (player == null || !player.isOnline()) {
            return;
        }
        String categoryId = fallback ? view.getText(KEY_CATEGORY) : preset.getId();
        plugin.submitFromInput(player, categoryId, view.getText(KEY_TITLE), view.getText(KEY_BODY),
                view.getText(KEY_PRIORITY));
    }

    // ------------------------------------------------------------------
    // 公共部件
    // ------------------------------------------------------------------

    /**
     * 正文区：可选图标 + 主说明 + 补充提示。
     *
     * @param hintArgs 追加到 hint 上的占位符（成对传入），不需要就省略
     */
    private List<DialogBody> headerBody(PluginConfig config, LangConfig lang, String bodyKey,
                                       String hintKey, Object... hintArgs) {
        List<DialogBody> body = new ArrayList<DialogBody>(3);
        if (config.isDialogShowIcon()) {
            body.add(iconBody(lang));
        }
        body.add(DialogBody.plainMessage(Component.text(lang.plain(bodyKey, hintArgs))));
        String hint = lang.plain(hintKey);
        if (!hint.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text(hint)));
        }
        return body;
    }

    /** 正文区的小图标；原版会把它渲染成物品贴图，鼠标悬停显示说明。 */
    private DialogBody iconBody(LangConfig lang) {
        return DialogBody.item(new ItemStack(Material.WRITABLE_BOOK))
                .description(DialogBody.plainMessage(Component.text(lang.plain("dialog.icon-label"))))
                .showDecorations(false)
                .showTooltip(true)
                .build();
    }

    /** 优先级下拉框的选项；默认档位由 {@code default: true} 或「无标签」决定。 */
    private List<SingleOptionDialogInput.OptionEntry> buildPriorityOptions(PluginConfig config, LangConfig lang) {
        List<Priority> priorities = config.getPriorities();
        Priority defaultPriority = config.getDefaultPriority();
        List<SingleOptionDialogInput.OptionEntry> options =
                new ArrayList<SingleOptionDialogInput.OptionEntry>(priorities.size());
        for (Priority priority : priorities) {
            String display = lang.plain("dialog.priority-option", "name", priority.getName());
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    priority.getId(), Component.text(display.trim()), priority == defaultPriority));
        }
        return options;
    }

    /** 分类下拉框的选项（只在单页式与回退模式下用得到）。 */
    private List<SingleOptionDialogInput.OptionEntry> buildCategoryOptions(PluginConfig config, LangConfig lang) {
        List<Category> categories = config.getCategories();
        List<SingleOptionDialogInput.OptionEntry> options =
                new ArrayList<SingleOptionDialogInput.OptionEntry>(categories.size());
        for (int index = 0; index < categories.size(); index++) {
            Category category = categories.get(index);
            String display = lang.plain("dialog.category-option",
                    "name", category.getName(),
                    "description", category.getDescription());
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    category.getId(), Component.text(display.trim()), index == 0));
        }
        return options;
    }

    /**
     * 下一个 tick 再开新窗口。
     * <p>
     * 按钮回调是在当前对话框即将关闭时触发的，紧接着发新对话框有可能和客户端
     * 处理「关闭」的时序打架，隔一个 tick 发最稳。
     */
    private void openLater(final Player player, final Category category) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    openForm(player, category);
                }
            }
        });
    }

    /** 同上，回到分类选择页。 */
    private void openPickerLater(final Player player) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    openPicker(player);
                }
            }
        });
    }

    /** 语言文件里的文字是给原版界面用的，不能带颜色代码。 */
    private static String plain(String raw) {
        return cn.aqcraft.iusse.util.Text.plain(raw);
    }

    /**
     * 「可能已有相同反馈」确认框。
     * <p>
     * 玩家点「仍然提交」才会真正投递 —— 这一步刻意绕过重复检测，
     * 因为玩家已经看过提示并确认这不是同一个问题。
     */
    public void openDuplicate(final Player player, final Category category, final Priority priority,
                              final String title, final String body, int existingNumber,
                              String existingTitle, String existingUrl) {
        final LangConfig lang = plugin.getLang();
        final String message = lang.plain("dialog.duplicate-body",
                "number", existingNumber,
                "title", existingTitle,
                "url", existingUrl == null ? "" : existingUrl);

        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder entry = factory.empty();
            entry.base(DialogBase.builder(Component.text(lang.plain("dialog.duplicate-title")))
                    .canCloseWithEscape(true)
                    .body(java.util.Collections.singletonList(
                            DialogBody.plainMessage(Component.text(message))))
                    .build());

            entry.type(DialogType.confirmation(
                    ActionButton.builder(Component.text(lang.plain("dialog.duplicate-confirm")))
                            .tooltip(Component.text(lang.plain("dialog.duplicate-confirm-tooltip")))
                            .width(BUTTON_WIDTH)
                            .action(DialogAction.customClick(
                                    (view, audience) -> {
                                        if (player.isOnline()) {
                                            plugin.submit(player, category, priority, title, body);
                                        }
                                    },
                                    ClickCallback.Options.builder().build()))
                            .build(),
                    ActionButton.builder(Component.text(lang.plain("dialog.duplicate-cancel")))
                            .tooltip(Component.text(lang.plain("dialog.duplicate-cancel-tooltip")))
                            .width(BUTTON_WIDTH)
                            .build()));
        });

        player.showDialog(dialog);
    }
}
