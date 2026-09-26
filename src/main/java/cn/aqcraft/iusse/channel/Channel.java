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
     *
     * @return 成功时的细节描述（如 Issue 链接），会展示给玩家
     * @throws Exception 投递失败，异常信息会展示给玩家
     */
    String submit(Submission submission) throws Exception;

    /** 连通性检查（/iusse status）。 */
    String checkStatus() throws Exception;
}
