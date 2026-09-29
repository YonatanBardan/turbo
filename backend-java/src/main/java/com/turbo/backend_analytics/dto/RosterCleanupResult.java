package com.turbo.backend_analytics.dto;

import com.turbo.backend_analytics.dto.Player.Player;

import java.util.List;
import java.util.Map;

public record RosterCleanupResult(
        List<Player> players,
        List<DetectionBox> detections
) {}
