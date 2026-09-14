package com.turbo.backend_analytics.service;

import com.turbo.backend_analytics.component.ball.BallStateTracker;
import com.turbo.backend_analytics.component.PlayerRosterBuilder;
import com.turbo.backend_analytics.component.PlayerRosterCleaner;
import com.turbo.backend_analytics.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AiAnalysisService {

    private final WebClient webClient;
    private final BallStateTracker ballStateTracker;
    private final PlayerRosterBuilder playerRosterBuilder;
    private final PlayerRosterCleaner playerRosterCleaner;

    public AiAnalysisService(
            WebClient.Builder webClientBuilder,
            BallStateTracker ballStateTracker,
            PlayerRosterBuilder playerRosterBuilder,
            PlayerRosterCleaner playerRosterCleaner
    ) {
        this.webClient = webClientBuilder.baseUrl("http://localhost:8000").build();
        this.ballStateTracker = ballStateTracker;
        this.playerRosterBuilder = playerRosterBuilder;
        this.playerRosterCleaner = playerRosterCleaner;
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

        if (pythonResponse == null || pythonResponse.detections() == null)
            throw new RuntimeException("Failed to get valid tracking data from Python YOLO engine.");

        double fps = pythonResponse.fps() != null ? pythonResponse.fps() : 30.0;

        List<Player> rawRoster = playerRosterBuilder.build(
                pythonResponse.detections(),
                pythonResponse.players()
        );
        RosterCleanupResult cleaned = playerRosterCleaner.clean(
                rawRoster,
                pythonResponse.detections()
        );

        GameAnalysis game = ballStateTracker.analyze(cleaned.detections(), fps);
        List<Player> players = applyStats(cleaned.players(), game.statsByPlayerId());

        return new VideoAnalysisResponse(
                fps,
                cleaned.detections(),
                game.shots(),
                players
        );
    }

    private List<Player> applyStats(List<Player> roster, Map<Integer, PlayerStats> statsById) {
        List<Player> withStats = new ArrayList<>(roster.size());
        for (Player player : roster) {
            PlayerStats stats = statsById.getOrDefault(player.id(), player.stats());
            withStats.add(new Player(player.id(), stats, player.appearanceVectors()));
        }
        for (Map.Entry<Integer, PlayerStats> extra : statsById.entrySet()) {
            boolean present = false;
            for (Player player : withStats) {
                if (player.id() == extra.getKey()) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                withStats.add(new Player(extra.getKey(), extra.getValue(), List.of()));
            }
        }
        return withStats;
    }

    public String renderScoreboardVideo(
            String absoluteVideoPath,
            List<Shot> finishedShots,
            List<DetectionBox> boxes,
            List<Player> players
    ) {

        List<Integer> playerIds = new ArrayList<>();
        if (players != null) {
            for (Player player : players) {
                playerIds.add(player.id());
            }
        }

        RenderRequest requestBody = new RenderRequest(absoluteVideoPath, finishedShots, boxes, playerIds);

        RenderResponse pythonResponse = this.webClient.post()
                .uri("/render")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(RenderResponse.class)
                .block();

        if (pythonResponse == null) {
            throw new RuntimeException("Failed to render the scoreboard video in Python.");
        }

        return pythonResponse.outputPath();
    }
}
