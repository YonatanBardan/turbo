package com.turbo.backend_analytics.service;

import com.turbo.backend_analytics.component.ShotTracker;
import com.turbo.backend_analytics.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class AiAnalysisService {

    private final WebClient webClient;
    private final ShotTracker shotTracker;

    public AiAnalysisService(WebClient.Builder webClientBuilder, ShotTracker shotTracker){

        this.webClient = webClientBuilder.baseUrl("http://localhost:8000").build();
        this.shotTracker = shotTracker;
    }

    // - Receives: full path of a video
    // - Returns: object typed VideoAnalysisResponse with analyzed frames data
    public VideoAnalysisResponse analyzeVideo(String absoluteVideoPath){

        // - creating the request
        Map requestBody = Map.of(
                "videoPath", absoluteVideoPath,
                "confidence", 0.4
        );

        // - Wait for post and saves the response from python
        TrackingResponse pythonResponse =  this.webClient.post()
                .uri("/analyze")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(TrackingResponse.class)
                .block();

        // - Calculate all the Shots occurred in the given video
        if (pythonResponse == null || pythonResponse.detections() == null)
            throw new RuntimeException("Failed to get valid tracking data from Python YOLO engine.");

        List<Shot> shots = shotTracker.extractAllShots(
                    pythonResponse.detections(),
                    pythonResponse.fps());

        return new VideoAnalysisResponse(
                pythonResponse.fps(),
                pythonResponse.detections(),
                shots
        );
    }

    public String renderScoreboardVideo(String absoluteVideoPath, List<Shot> finishedShots) {

        // - Inserting the video path and the calculated shots into the DTO
        RenderRequest requestBody = new RenderRequest(absoluteVideoPath, finishedShots);

        // - Sending the same format WebClient POST request
        RenderResponse pythonResponse = this.webClient.post()
                .uri("/render")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(RenderResponse.class)
                .block();

        // - Checks for failure and returns the final video's path
        if (pythonResponse == null) {
            throw new RuntimeException("Failed to render the scoreboard video in Python.");
        }

        return pythonResponse.outputPath();
    }
}
