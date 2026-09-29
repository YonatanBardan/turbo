package com.turbo.backend_analytics.dto.Player;

import java.util.List;

public record Player(
        int id,
        PlayerStats stats,
        List<ScoredAppearanceVector> appearanceVectors
) {}
