package com.turbo.backend_analytics.dto;

import com.turbo.backend_analytics.dto.Player.PlayerStats;
import com.turbo.backend_analytics.dto.Shot.Shot;

import java.util.List;
import java.util.Map;

public record GameAnalysis(
        List<Shot> shots,
        Map<Integer, PlayerStats> statsByPlayerId,
        List<BallFrameState> ballStates
) {}
