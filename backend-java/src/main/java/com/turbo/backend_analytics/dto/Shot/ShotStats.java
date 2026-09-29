package com.turbo.backend_analytics.dto.Shot;

import java.util.List;

public record ShotStats(
        int totalAttempts,
        int madeShots,
        int missedShots,
        double averageMakeProbability,
        List<Shot> Shots
    ) {}
