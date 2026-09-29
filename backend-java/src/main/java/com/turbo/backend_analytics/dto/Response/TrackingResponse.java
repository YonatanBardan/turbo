package com.turbo.backend_analytics.dto.Response;

import com.turbo.backend_analytics.dto.Court.CourtKeypoints;
import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Player.PlayerAppearance;

import java.util.List;

public record TrackingResponse(
        String status,
        Integer totalFrames,
        List<DetectionBox> detections,
        Double fps,
        List<PlayerAppearance> players,
        List<CourtKeypoints> courtKeypoints
) {}
