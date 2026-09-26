package cn.aqcraft.iusse.tracking;

/**
 * 反馈统计（{@code /iusse stats}）。
 * <p>
 * 全部基于本地跟踪记录计算，不消耗 GitHub API 额度。
 */
public class IssueStats {

    public final int total;
    public final int open;
    public final int closed;
    /** 已超过 SLA 阈值仍未处理的条数。 */
    public final int overdue;
    public final int last7Days;
    /** 平均处理时长（毫秒），0 表示还没有已处理的样本。 */
    public final long averageHandleMillis;

    private IssueStats(int total, int open, int closed, int overdue, int last7Days, long averageHandleMillis) {
        this.total = total;
        this.open = open;
        this.closed = closed;
        this.overdue = overdue;
        this.last7Days = last7Days;
        this.averageHandleMillis = averageHandleMillis;
    }

    public static IssueStats compute(java.util.List<IssueRecord> records, int slaHours, long now) {
        int total = 0;
        int open = 0;
        int closed = 0;
        int overdue = 0;
        int last7Days = 0;
        long handledSum = 0L;
        int handledCount = 0;

        long sevenDaysAgo = now - 7L * 86400000L;
        long slaMillis = Math.max(1, slaHours) * 3600000L;

        for (IssueRecord record : records) {
            total++;
            if (record.submittedAt >= sevenDaysAgo) {
                last7Days++;
            }
            if (record.closed) {
                closed++;
                if (record.closedAt > 0 && record.submittedAt > 0 && record.closedAt >= record.submittedAt) {
                    handledSum += record.closedAt - record.submittedAt;
                    handledCount++;
                }
            } else {
                open++;
                if (record.submittedAt > 0 && now - record.submittedAt > slaMillis) {
                    overdue++;
                }
            }
        }

        return new IssueStats(total, open, closed, overdue, last7Days,
                handledCount == 0 ? 0L : handledSum / handledCount);
    }

    /** 距今多少小时（用于「已等待 N 小时」）。 */
    public static long hoursSince(long from, long now) {
        if (from <= 0) {
            return 0L;
        }
        return Math.max(0L, (now - from) / 3600000L);
    }

    /** 把毫秒时长写成「3 天 4 小时」这种人话。 */
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "暂无数据";
        }
        long minutes = millis / 60000L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        if (days > 0) {
            return days + " 天 " + (hours % 24L) + " 小时";
        }
        if (hours > 0) {
            return hours + " 小时 " + (minutes % 60L) + " 分钟";
        }
        return Math.max(1L, minutes) + " 分钟";
    }
}
