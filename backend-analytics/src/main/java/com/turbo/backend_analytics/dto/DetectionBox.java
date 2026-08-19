package com.turbo.backend_analytics.dto;

import java.util.List;

public record DetectionBox(
        int frameIndex,
        int classId,
        double confidence,
        double x,
        double y,
        double width,
        double height
) {}