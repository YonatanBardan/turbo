package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Shot;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ShotTracker {

    private final BallStateTracker ballStateTracker;

    public ShotTracker(BallStateTracker ballStateTracker) {
        this.ballStateTracker = ballStateTracker;
    }

    public List<Shot> extractAllShots(List<DetectionBox> detections, double fps) {
        return ballStateTracker.analyze(detections, fps).shots();
    }
}
