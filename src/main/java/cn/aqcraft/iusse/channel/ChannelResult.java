package cn.aqcraft.iusse.channel;

/**
 * 单个渠道的投递结果。
 */
public class ChannelResult {

    private final String channelId;
    private final String displayName;
    private final boolean success;
    private final String detail;

    public ChannelResult(String channelId, String displayName, boolean success, String detail) {
        this.channelId = channelId;
        this.displayName = displayName;
        this.success = success;
        this.detail = detail;
    }

    public static ChannelResult success(Channel channel, String detail) {
        return new ChannelResult(channel.getId(), channel.getDisplayName(), true, detail);
    }

    public static ChannelResult failure(Channel channel, String detail) {
        return new ChannelResult(channel.getId(), channel.getDisplayName(), false, detail);
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
}
