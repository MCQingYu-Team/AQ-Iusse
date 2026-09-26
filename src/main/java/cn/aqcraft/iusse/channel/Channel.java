package cn.aqcraft.iusse.channel;

/**
 * 反馈投递渠道。
 * <p>
 * 实现类只需关注「怎么把一条 {@link Submission} 送出去」，
 * 线程调度、结果汇总由 {@link ChannelManager} 负责。
 */
public interface Channel {

    /** 配置里的渠道 id，如 github / discord / onebot。 */
    String getId();

    /** 展示名（来自语言文件），用于给玩家回执。 */
    String getDisplayName();

    /** 该渠道是否已在 config.yml 中启用且配置完整。 */
    boolean isEnabled();

    /** 为什么不可用（用于启动日志与 /iusse status）。 */
    String getDisabledReason();

    /** 启动渠道（例如开启 WebSocket 监听），失败由调用方记录。 */
    void start() throws Exception;

    /** 关闭渠道，释放端口与连接。 */
    void stop();

    /**
     * 同步投递一条反馈。
     * <p>
     * 渠道按 {@code github → discord → onebot} 的顺序逐个执行，
     * 因此后面的渠道可以通过 {@link Submission#getLinks()} 拿到前面渠道产生的链接
     * （典型场景：QQ 群消息里带上刚创建的 GitHub Issue 地址）。
     *
     * @return 投递结果；失败请直接抛异常，由调用方统一汇总
     */
    ChannelResult submit(Submission submission) throws Exception;

    /** 连通性检查（/iusse status）。 */
    String checkStatus() throws Exception;

    /**
     * 向管理员推送一条系统通知（例如反馈处理超时提醒）。
     * <p>
     * 与 {@link #submit(Submission)} 不同，这里发的是服务端自己要说的话，
     * 不携带玩家反馈内容。不支持的渠道用默认实现（直接返回 false）即可。
     *
     * @param message 已上色的纯文本消息
     * @return 是否至少送达一个目标
     */
    default boolean notifyAdmins(String message) {
        return false;
    }

    /**
     * 按目标类型推送系统通知。
     *
     * @param groups   是否发到群 / 频道（Discord 只有这一种目标，传 false 则不发）
     * @param privates 是否私信个人（只有 OneBot 支持）
     * @return 是否至少送达一个目标
     */
    default boolean notifyAdmins(String message, boolean groups, boolean privates) {
        return notifyAdmins(message);
    }

    /**
     * 向指定的社交账号私信一条消息。
     * <p>
     * 目前只有 OneBot 支持（账号即 QQ 号），用于在玩家不在线时把反馈进展推给他。
     *
     * @return 是否送达
     */
    default boolean sendPrivate(long accountId, String message) {
        return false;
    }
}
