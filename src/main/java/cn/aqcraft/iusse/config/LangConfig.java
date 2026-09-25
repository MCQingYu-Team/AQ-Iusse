package cn.aqcraft.iusse.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.util.Text;

/**
 * 语言文件读取。
 * <p>
 * 插件所有面向玩家的文本都集中在单个语言文件（默认 {@code lang.yml}）里，
 * 修改文案无需改动代码或 {@code config.yml}。
 * 若数据目录下的语言文件缺失或缺少某个键，会自动回退到 jar 内置的同名文件。
 */
public class LangConfig {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final AqIssuePlugin plugin;

    private String fileName = "lang.yml";
    private File file;
    private FileConfiguration config;

    public LangConfig(AqIssuePlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** 按 config.yml 中的 {@code language-file} 重新加载语言文件。 */
    public void load() {
        String configured = plugin.getConfig().getString("language-file", "lang.yml");
        if (configured == null || configured.trim().isEmpty()) {
            configured = "lang.yml";
        }
        this.fileName = configured.trim();
        this.file = new File(plugin.getDataFolder(), fileName);

        if (!file.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("jar 内未找到语言文件 " + fileName + "，将使用内置默认文本。");
            }
        }

        this.config = YamlConfiguration.loadConfiguration(file);

        // 用 jar 内置的 lang.yml 作为默认值来源，保证缺键时有兜底文案
        InputStream bundled = plugin.getResource("lang.yml");
        if (bundled != null) {
            try {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
            } finally {
                try {
                    bundled.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 当前语言文件的文件名。 */
    public String getFileName() {
        return fileName;
    }

    /** 语言标识（meta.locale），仅用于日志展示。 */
    public String getLocale() {
        return config.getString("meta.locale", "unknown");
    }

    /** 取原始文本（未上色）。 */
    public String raw(String key) {
        String value = config.getString(key);
        return value == null ? key : value;
    }

    /** 取上色后的文本。 */
    public String text(String key) {
        return Text.color(raw(key));
    }

    /** 取上色并替换占位符后的文本。 */
    public String text(String key, Object... placeholders) {
        return applyPlaceholders(Text.color(raw(key)), placeholders);
    }

    /** 取带前缀、已上色、已替换占位符的文本。 */
    public String prefixed(String key, Object... placeholders) {
        return prefix() + text(key, placeholders);
    }

    /**
     * 取去掉颜色代码的纯文本，供 Paper 对话框使用
     * （对话框由原版界面渲染，不认 &amp; 与 § 颜色代码）。
     */
    public String plain(String key) {
        return Text.plain(raw(key));
    }

    /** 取去色并替换占位符后的纯文本。 */
    public String plain(String key, Object... placeholders) {
        return applyPlaceholders(Text.plain(raw(key)), placeholders);
    }

    /** 消息前缀。 */
    public String prefix() {
        return Text.color(config.getString("prefix", ""));
    }

    /** 取字符串列表（已上色）。 */
    public List<String> list(String key) {
        List<String> raw = config.getStringList(key);
        if (raw == null || raw.isEmpty()) {
            String single = config.getString(key);
            if (single != null) {
                raw = Collections.singletonList(single);
            } else {
                return Collections.emptyList();
            }
        }
        List<String> colored = new ArrayList<String>(raw.size());
        for (String line : raw) {
            colored.add(Text.color(line));
        }
        return colored;
    }

    /** 指令帮助文本。 */
    public List<String> usageLines() {
        return list("command.usage");
    }

    /** 把 {@code {key}} 占位符替换为实际值，参数成对出现：key1, value1, key2, value2... */
    public static String applyPlaceholders(String template, Object... placeholders) {
        if (template == null || placeholders == null) {
            return template;
        }
        String result = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            result = Text.replace(result, String.valueOf(placeholders[i]), placeholders[i + 1]);
        }
        return result;
    }
}
