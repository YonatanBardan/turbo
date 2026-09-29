package com.turbo.backend_analytics.dto.Player;

public record ScoredAppearanceVector(
        double score,
        float[] vector
) {}
