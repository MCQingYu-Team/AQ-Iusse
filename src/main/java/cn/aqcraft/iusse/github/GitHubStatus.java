package cn.aqcraft.iusse.github;

/**
 * GitHub 仓库连通性检查结果。
 */
public class GitHubStatus {

    private final boolean ok;
    private final String repoFullName;
    private final String authDescription;
    private final String rateRemaining;
    private final String message;

    private GitHubStatus(boolean ok, String repoFullName, String authDescription,
                         String rateRemaining, String message) {
        this.ok = ok;
        this.repoFullName = repoFullName;
        this.authDescription = authDescription;
        this.rateRemaining = rateRemaining;
        this.message = message;
    }

    public static GitHubStatus success(String repoFullName, String authDescription, String rateRemaining) {
        return new GitHubStatus(true, repoFullName, authDescription, rateRemaining, null);
    }

    public static GitHubStatus failure(String message) {
        return new GitHubStatus(false, null, null, null, message);
    }

    public boolean isOk() {
        return ok;
    }

    public String getRepoFullName() {
        return repoFullName;
    }

    public String getAuthDescription() {
        return authDescription;
    }

    public String getRateRemaining() {
        return rateRemaining;
    }

    public String getMessage() {
        return message;
    }
}
