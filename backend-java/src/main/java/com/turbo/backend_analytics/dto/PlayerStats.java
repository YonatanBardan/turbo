package com.turbo.backend_analytics.dto;

import java.util.List;

public record PlayerStats(
        int assists,
        List<Shot> shots
) {}
