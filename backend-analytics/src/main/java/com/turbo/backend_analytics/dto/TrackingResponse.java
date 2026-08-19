package com.turbo.backend_analytics.dto;

import java.util.List;

public record TrackingResponse(
        String status,
        int totalFrames,
        List<DetectionBox> detections,
        int fps
        ) {}
