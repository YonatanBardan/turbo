package com.turbo.backend_analytics.dto;

import java.util.List;

public record RenderRequest(
        String videoPath,
        List<Shot> shots
) {}