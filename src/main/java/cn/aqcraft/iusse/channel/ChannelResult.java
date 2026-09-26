package cn.aqcraft.iusse.channel;

/**
 * 单个渠道的投递结果。
 */
public class ChannelResult {

    private final String channelId;
    private final String displayName;
    private final boolean success;
    private final String detail;
    /** 该渠道产生的可访问链接（如 GitHub Issue 地址），没有则为 null。 */
    private final String link;

    private ChannelResult(String channelId, String displayName, boolean success, String detail, String link) {
        this.channelId = channelId;
        this.displayName = displayName;
        this.success = success;
        this.detail = detail;
        this.link = link;
    }

    public static ChannelResult success(Channel channel, String detail) {
        return new ChannelResult(channel.getId(), channel.getDisplayName(), true, detail, null);
    }

    public static ChannelResult success(Channel channel, String detail, String link) {
        return new ChannelResult(channel.getId(), channel.getDisplayName(), true, detail, link);
    }

    public static ChannelResult failure(Channel channel, String detail) {
        return new ChannelResult(channel.getId(), channel.getDisplayName(), false, detail, null);
    }

    public String getChannelId() {
        return channelId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getDetail() {
        return detail;
    }

    /** 投递成功时产生的链接，可能为 {@code null}。 */
    public String getLink() {
        return link;
    }
}
