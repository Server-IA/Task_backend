package com.taskflow.api.dto.response;

/**
 * Avance simple: issues completados frente al total.
 */
public record ProgressResponse(
        long totalIssues,
        long completedIssues,
        int percentage
) {

    public static ProgressResponse of(long total, long completed) {
        int percentage = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);
        return new ProgressResponse(total, completed, percentage);
    }
}
