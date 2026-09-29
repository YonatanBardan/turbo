package com.turbo.backend_analytics.dto.Player;

import java.util.List;

public record PlayerAppearance(
        int id,
        List<ScoredAppearanceVector> appearanceVectors
) {}
