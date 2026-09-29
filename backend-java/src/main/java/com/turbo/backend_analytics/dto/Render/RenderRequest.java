package com.turbo.backend_analytics.dto.Render;

import com.turbo.backend_analytics.dto.BallFrameState;
import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Shot.Shot;

import java.util.List;

public record RenderRequest(
        String videoPath,
        List<Shot> shots,
        List<DetectionBox> detections,
        List<Integer> playerIds,
        List<BallFrameState> ballStates
) {}
