package com.turbo.backend_analytics.dto;

import java.util.List;
import java.util.Map;

public record GameAnalysis(
        List<Shot> shots,
        Map<Integer, PlayerStats> statsByPlayerId
) {}
