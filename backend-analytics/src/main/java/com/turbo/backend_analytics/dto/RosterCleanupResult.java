package com.turbo.backend_analytics.dto;

import java.util.List;
import java.util.Map;

public record RosterCleanupResult(
        List<Player> players,
        List<DetectionBox> detections,
        Map<Integer, Integer> idAlias
) {}
