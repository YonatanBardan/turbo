package com.turbo.backend_analytics.dto;

import java.util.List;

public record TrackingResponse(
        String status,
        Integer totalFrames,
        List<DetectionBox> detections,
        Integer fps
        ) {}
