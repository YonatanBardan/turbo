package com.turbo.backend_analytics.dto.Response;

import com.turbo.backend_analytics.dto.BallFrameState;
import com.turbo.backend_analytics.dto.Court.CourtKeypoints;
import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Player.Player;
import com.turbo.backend_analytics.dto.Shot.Shot;

import java.util.List;

// - Object translation returning from model process on a video
public record VideoAnalysisResponse(

    double fps,
    int totalFrames,
    List<DetectionBox> boxes,
    List<Shot> shots,
    List<Player> players,
    List<BallFrameState> ballStates,
    List<CourtKeypoints> courtKeypoints

){}
