package com.turbo.backend_analytics.dto.Player;

import com.turbo.backend_analytics.dto.Shot.Shot;

import java.util.List;

public record PlayerStats(
        int assists,
        List<Shot> shots
) {}
