package com.turbo.backend_analytics.dto;

import java.util.List;

public record PlayerAppearance(
        int id,
        List<ScoredAppearanceVector> appearanceVectors
) {}
