package com.turbo.backend_analytics.dto.Court;

import java.util.List;

public record CourtKeypoints(
        int frameIndex,
        List<Point> corners
) {}