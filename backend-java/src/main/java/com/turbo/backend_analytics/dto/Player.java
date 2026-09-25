package com.turbo.backend_analytics.dto;

import java.util.List;

public record Player(
        int id,
        PlayerStats stats,
        List<ScoredAppearanceVector> appearanceVectors
) {}
