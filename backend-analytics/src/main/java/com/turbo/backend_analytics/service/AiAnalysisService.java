package com.turbo.backend_analytics.service;

import com.turbo.backend_analytics.dto.TrackingResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;

@Service
public class AiAnalysisService {

    private final WebClient webClient;

    public AiAnalysisService(WebClient.Builder webClientBuilder){

        this.webClient = WebClient.builder().baseUrl("http://localhost:8000").build();
    }

    // - Receives: full path of a video
    // - Returns: object typed TrackingResponse with analyzed frames data
    public TrackingResponse analyzeVideo(String absoluteVideoPath){

        // - creating the request
        Map requestBody = Map.of(
                "video_path", absoluteVideoPath,
                "confidence", 0.4
        );

        // - Wait for post
        return this.webClient.post()
                .uri("/analyze")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(TrackingResponse.class)
                .block();
    }
}
