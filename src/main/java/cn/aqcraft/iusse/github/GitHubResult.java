package cn.aqcraft.iusse.github;

/**
 * GitHub API 调用结果。
 */
public class GitHubResult {

    private final boolean success;
    private final int statusCode;
    private final int issueNumber;
    private final String issueUrl;
    private final String message;

    private GitHubResult(boolean success, int statusCode, int issueNumber, String issueUrl, String message) {
        this.success = success;
        this.statusCode = statusCode;
        this.issueNumber = issueNumber;
        this.issueUrl = issueUrl;
        this.message = message;
    }

    public static GitHubResult ok(int statusCode, int issueNumber, String issueUrl) {
        return new GitHubResult(true, statusCode, issueNumber, issueUrl, null);
    }

    public static GitHubResult fail(int statusCode, String message) {
        return new GitHubResult(false, statusCode, 0, null, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public int getIssueNumber() {
        return issueNumber;
    }

    public String getIssueUrl() {
        return issueUrl;
    }

    /** 失败原因（已本地化为可读中文）。 */
    public String getMessage() {
        return message;
    }
}
