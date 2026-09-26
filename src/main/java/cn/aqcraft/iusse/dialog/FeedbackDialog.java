package cn.aqcraft.iusse.dialog;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.config.Category;
import cn.aqcraft.iusse.config.LangConfig;
import cn.aqcraft.iusse.config.PluginConfig;

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
 * 反馈对话框：选分类、填标题与内容，点「提交」直接创建 Issue。
 * <p>
 * 使用 Paper 原生 Dialog API（1.21.7 引入，签名在 1.21.7 ~ 26.x 之间保持兼容），
 * 因此不需要任何版本适配代码，也不需要自己画 GUI。
 */
public class FeedbackDialog {

    /** 对话框输入项的键名，同时也是回调里读取值的键。 */
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_TITLE = "title";
    private static final String KEY_BODY = "body";

    /** 原版按钮的像素宽度，100 与原版「确认 / 取消」按钮一致。 */
    private static final int BUTTON_WIDTH = 100;

    private final AqIssuePlugin plugin;

    public FeedbackDialog(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    /** 向玩家弹出反馈对话框。 */
    public void open(Player player) {
        final PluginConfig config = plugin.getPluginConfig();
        final LangConfig lang = plugin.getLang();

        final List<SingleOptionDialogInput.OptionEntry> options = buildCategoryOptions(config, lang);
        final int maxTitleLength = config.getMaxTitleLength();
        final int maxBodyLength = config.getMaxBodyLength();

        List<DialogInput> inputs = new ArrayList<DialogInput>();
        inputs.add(DialogInput.singleOption(KEY_CATEGORY,
                Component.text(lang.plain("dialog.category-label")), options).build());
        inputs.add(DialogInput.text(KEY_TITLE, Component.text(lang.plain("dialog.title-label")))
                .maxLength(maxTitleLength)
                .build());
        inputs.add(DialogInput.text(KEY_BODY, Component.text(lang.plain("dialog.body-label")))
                .maxLength(maxBodyLength)
                .multiline(TextDialogInput.MultilineOptions.create(Integer.valueOf(6), Integer.valueOf(200)))
                .build());

        Dialog dialog = Dialog.create(factory -> {
            DialogRegistryEntry.Builder entry = factory.empty();
            entry.base(DialogBase.builder(Component.text(lang.plain("dialog.title")))
                    .canCloseWithEscape(true)
                    .body(java.util.Collections.singletonList(
                            DialogBody.plainMessage(Component.text(lang.plain("dialog.body")))))
                    .inputs(inputs)
                    .build());

            entry.type(DialogType.confirmation(
                    ActionButton.builder(Component.text(lang.plain("dialog.confirm")))
                            .tooltip(Component.text(lang.plain("dialog.confirm-tooltip")))
                            .width(BUTTON_WIDTH)
                            .action(DialogAction.customClick(
                                    (view, audience) -> onSubmit(player, view),
                                    ClickCallback.Options.builder().build()))
                            .build(),
                    ActionButton.builder(Component.text(lang.plain("dialog.cancel")))
                            .tooltip(Component.text(lang.plain("dialog.cancel-tooltip")))
                            .width(BUTTON_WIDTH)
                            .build()));
        });

        player.showDialog(dialog);
    }

    /**
     * 「可能已有相同反馈」确认框。
     * <p>
     * 玩家点「仍然提交」才会真正投递 —— 这一步刻意绕过重复检测，
     * 因为玩家已经看过提示并确认这不是同一个问题。
     */
    public void openDuplicate(final Player player, final Category category, final String title,
                              final String body, int existingNumber, String existingTitle,
                              String existingUrl) {
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
                                            plugin.submit(player, category, title, body);
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

    /** 「提交」按钮回调。 */
    private void onSubmit(Player player, DialogResponseView view) {
        if (player == null || !player.isOnline()) {
            return;
        }
        plugin.submitFromInput(player,
                view.getText(KEY_CATEGORY),
                view.getText(KEY_TITLE),
                view.getText(KEY_BODY));
    }
}
