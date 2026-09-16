package com.metatron.workforce.release;

public record MergeAuthorization(String assignmentId, String repository, int prNumber, String expectedHeadSha) {}
