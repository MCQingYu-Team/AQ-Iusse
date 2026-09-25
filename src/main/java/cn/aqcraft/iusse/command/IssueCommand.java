package cn.aqcraft.iusse.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.config.Category;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.github.GitHubStatus;

/**
 * {@code /iusse} 指令。
 * <ul>
 *   <li>无参数 —— 打开反馈对话框</li>
 *   <li>{@code submit <分类> <标题>|<内容>} —— 一行式提交，适合快捷键 / 脚本</li>
 *   <li>{@code url} / {@code status} / {@code reload} / {@code help}</li>
 * </ul>
 */
public class IssueCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE_SUBMIT = "/iusse submit <分类> <标题>|<内容>";

    private static final List<String> SUB_COMMANDS =
            Arrays.asList("submit", "url", "status", "reload", "help");

    private final AqIssuePlugin plugin;

    public IssueCommand(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getLang().text("general.player-only"));
            return true;
        }
        Player player = (Player) sender;

        if (args.length == 0) {
            plugin.openDialog(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help":
            case "?":
                sendUsage(player);
                return true;
            case "submit":
                handleSubmit(player, args);
                return true;
            case "url":
                plugin.send(player, "repository.url", "url", plugin.getPluginConfig().getRepoUrl());
                return true;
            case "status":
                if (requireAdmin(player)) {
                    handleStatus(player);
                }
                return true;
            case "reload":
                if (requireAdmin(player)) {
                    plugin.reloadAll();
                    plugin.send(player, "general.reloaded");
                }
                return true;
            default:
                sendUsage(player);
                return true;
        }
    }

    // ------------------------------------------------------------------

    private void handleSubmit(Player player, String[] args) {
        if (args.length < 3) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_SUBMIT);
            return;
        }
        String categoryId = args[1];
        String rest = join(args, 2);
        int separator = rest.indexOf('|');
        if (separator < 0) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_SUBMIT);
            return;
        }
        String title = rest.substring(0, separator).trim();
        String body = rest.substring(separator + 1).trim();
        plugin.submitFromInput(player, categoryId, title, body);
    }

    private void handleStatus(final Player player) {
        plugin.send(player, "status.checking");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final GitHubStatus status = plugin.getGitHubClient().checkStatus();
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (status.isOk()) {
                            plugin.send(player, "status.ok",
                                    "repo", status.getRepoFullName(),
                                    "auth", status.getAuthDescription(),
                                    "remaining", status.getRateRemaining());
                        } else {
                            plugin.send(player, "status.failed", "reason", status.getMessage());
                        }
                    }
                });
            }
        });
    }

    private boolean requireAdmin(Player player) {
        if (player.hasPermission("aqissue.admin")) {
            return true;
        }
        plugin.send(player, "general.no-permission");
        return false;
    }

    private void sendUsage(Player player) {
        for (String line : plugin.getLang().usageLines()) {
            player.sendMessage(line);
        }
    }

    private static String join(String[] args, int from) {
        StringBuilder builder = new StringBuilder();
        for (int index = from; index < args.length; index++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[index]);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<String>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            boolean admin = sender.hasPermission("aqissue.admin");
            for (String sub : SUB_COMMANDS) {
                if (!admin && ("status".equals(sub) || "reload".equals(sub))) {
                    continue;
                }
                if (sub.startsWith(prefix)) {
                    result.add(sub);
                }
            }
            return result;
        }

        if (args.length == 2 && "submit".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            PluginConfig config = plugin.getPluginConfig();
            for (Category category : config.getCategories()) {
                if (category.getId().startsWith(prefix)) {
                    result.add(category.getId());
                }
            }
            return result;
        }

        return result;
    }
}
