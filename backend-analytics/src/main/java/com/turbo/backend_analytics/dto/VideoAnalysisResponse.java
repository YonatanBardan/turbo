package com.turbo.backend_analytics.dto;

import java.util.List;

// - Object translation returning from model process on a video
public record VideoAnalysisResponse(

    double fps,
    List<DetectionBox> boxes,
    List<Shot> shots

    ){}

