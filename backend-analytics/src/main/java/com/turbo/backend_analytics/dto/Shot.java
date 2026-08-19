package com.turbo.backend_analytics.dto;

public record Shot(

    DetectionBox shooter,
    double makeProbability,
    String time,
    int frameIndex,
    boolean isMake
    ) {}
