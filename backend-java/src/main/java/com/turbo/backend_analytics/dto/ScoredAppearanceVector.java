package com.turbo.backend_analytics.dto;

public record ScoredAppearanceVector(
        double score,
        float[] vector
) {}
