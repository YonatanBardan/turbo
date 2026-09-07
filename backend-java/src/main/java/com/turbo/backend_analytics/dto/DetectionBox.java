package com.turbo.backend_analytics.dto;

public record DetectionBox(
        int frameIndex,
        int classId,
        double confidence,
        double x,
        double y,
        double width,
        double height,
        int trackId
) {}
