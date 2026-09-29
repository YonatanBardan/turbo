package com.turbo.backend_analytics.service;

import com.turbo.backend_analytics.component.CourtHomograph;
import com.turbo.backend_analytics.component.PlayerRoster.PlayerRosterBuilder;
import com.turbo.backend_analytics.component.PlayerRoster.PlayerRosterCleaner;
import com.turbo.backend_analytics.component.ball.BallStateTracker;
import com.turbo.backend_analytics.dto.BallFrameState;
import com.turbo.backend_analytics.dto.Court.CourtKeypoints;
import com.turbo.backend_analytics.dto.Court.Point;
import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.GameAnalysis;
import com.turbo.backend_analytics.dto.Player.Player;
import com.turbo.backend_analytics.dto.Player.PlayerStats;
import com.turbo.backend_analytics.dto.Render.RenderRequest;
import com.turbo.backend_analytics.dto.Render.RenderResponse;
import com.turbo.backend_analytics.dto.Response.TrackingResponse;
import com.turbo.backend_analytics.dto.Response.VideoAnalysisResponse;
import com.turbo.backend_analytics.dto.RosterCleanupResult;
import com.turbo.backend_analytics.dto.Shot.Shot;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Service
public class AiAnalysisService {

    private final WebClient webClient;
    private final BallStateTracker ballStateTracker;
    private final PlayerRosterBuilder playerRosterBuilder;
    private final PlayerRosterCleaner playerRosterCleaner;
    private final CourtHomograph courtHomograph;

    public AiAnalysisService(
            WebClient.Builder webClientBuilder,
            BallStateTracker ballStateTracker,
            PlayerRosterBuilder playerRosterBuilder,
            PlayerRosterCleaner playerRosterCleaner,
            CourtHomograph courtHomograph
    ) {
        this.webClient = webClientBuilder.baseUrl("http://localhost:8000").build();
        this.ballStateTracker = ballStateTracker;
        this.playerRosterBuilder = playerRosterBuilder;
        this.playerRosterCleaner = playerRosterCleaner;
        this.courtHomograph = courtHomograph;
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
        TrackingResponse pythonResponse = this.webClient.post()
                .uri("/analyze")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(TrackingResponse.class)
                .block();

        if (pythonResponse == null || pythonResponse.detections() == null)
            throw new RuntimeException("Failed to get valid tracking data from Python YOLO engine.");
        
        // Gets court data, fps, given roster and total frames
        List<CourtKeypoints> courtData = pythonResponse.courtKeypoints() != null ? pythonResponse.courtKeypoints() : List.of();
        double fps = pythonResponse.fps() != null ? pythonResponse.fps() : 30.0;
        List<Player> rawRoster = playerRosterBuilder.build(pythonResponse.detections(), pythonResponse.players());
        int totalFrames = pythonResponse.totalFrames() != null ? pythonResponse.totalFrames() : 0;

        // Roster cleaning
        System.out.println("Roster before clean: " + rosterSummary(rawRoster));
        RosterCleanupResult cleaned = playerRosterCleaner.clean(rawRoster, pythonResponse.detections());
        System.out.println("Roster after clean: " + rosterSummary(cleaned.players()));

        // Run ball tracking and shot detection
        GameAnalysis game = ballStateTracker.analyze(cleaned.detections(), fps);

        // Maps shots and stats to 2D court coordinates
        List<Shot> mappedShots = mapShotsTo2D(game.shots(), courtData);
        Map<Integer, PlayerStats> mappedStatsById = mapPlayerStatsWith2DLocation(game.statsByPlayerId(), courtData);

        // Applies stats to players with updated 2D court coordinates
        List<Player> players = applyStats(cleaned.players(), mappedStatsById);


        return new VideoAnalysisResponse(
                fps,
                totalFrames,
                cleaned.detections(),
                mappedShots,
                players,
                game.ballStates(),
                courtData
        );
    }


    // Renders the final video 
    public String renderScoreboardVideo(String absoluteVideoPath, List<Shot> finishedShots, List<DetectionBox> boxes,
                                        List<Player> players, List<BallFrameState> ballStates) {

        List<Integer> playerIds = new ArrayList<>();
        if (players != null) {
            for (Player player : players) {
                playerIds.add(player.id());
            }
        }

        RenderRequest requestBody = new RenderRequest(
                absoluteVideoPath,
                finishedShots,
                boxes,
                playerIds,
                ballStates != null ? ballStates : List.of()
        );

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


    // ------------------------------------------------------------
    // Helper functions
    // ------------------------------------------------------------ 

    // Prints the roster summary
    private static String rosterSummary(List<Player> roster) {
        if (roster == null || roster.isEmpty()) {
            return "[]";
        }
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < roster.size(); i++) {
            Player player = roster.get(i);
            int vectors = player.appearanceVectors() == null ? 0 : player.appearanceVectors().size();
            if (i > 0) {
                text.append(", ");
            }
            text.append(player.id()).append(" (").append(vectors).append(" vectors)");
        }
        return text.append("]").toString();
    }

    // Applies stats to players
    private List<Player> applyStats(List<Player> roster, Map<Integer, PlayerStats> statsById) {
        List<Player> withStats = new ArrayList<>(roster.size());
        for (Player player : roster) {
            PlayerStats stats = statsById.getOrDefault(player.id(), player.stats());
            withStats.add(new Player(player.id(), stats, player.appearanceVectors()));
        }
        return withStats;
    }

    // Maps shots to the 2D court coordinates
    private List<Shot> mapShotsTo2D(List<Shot> shots, List<CourtKeypoints> courtData) {
        if (shots == null || shots.isEmpty() || courtData == null || courtData.isEmpty())
            return List.of();

        List<Shot> mappedShots = new ArrayList<>(shots.size());
        for (Shot shot : shots) {
            double mapped_x = 0.0;
            double mapped_y = 0.0;

            // Map from the take-off box (feet on the floor) when available, the release box is mid-jump
            DetectionBox feetBox = shot.groundBox() != null ? shot.groundBox() : shot.shooter();
            if (feetBox != null) {
                Point shooterLocation = courtHomograph.calculateShotLocation(feetBox, courtData);
                if (shooterLocation != null) {
                    mapped_x = shooterLocation.x();
                    mapped_y = shooterLocation.y();
                }
            }

            // Rebuild the Shot record with the new coordinate parameters
            Shot mappedShot = new Shot(
                    shot.shooter(),
                    shot.groundBox(),
                    shot.makeProbability(),
                    shot.time(),
                    shot.frameIndex(),
                    shot.isMake(),
                    shot.shooterTrackId(),
                    shot.passerTrackId(),
                    shot.assist(),
                    mapped_x,
                    mapped_y
            );

            mappedShots.add(mappedShot);
        }
        return mappedShots;
    }

    // Maps player stats with the updated 2D court coordinates
    private Map<Integer, PlayerStats> mapPlayerStatsWith2DLocation(Map<Integer, PlayerStats> statsById, List<CourtKeypoints> courtData) {
        if (statsById == null || statsById.isEmpty() || courtData == null || courtData.isEmpty())
            return Map.of();

        Map<Integer, PlayerStats> mappedStatsById = new HashMap<>();

        // .entrySet() returns a set of the map's key-value pairs
        // .Entry is a pair of a key and a value <Integer, PlayerStats>
        for (Map.Entry<Integer, PlayerStats> entry : statsById.entrySet()) { // for each an entry in the Set of key-value pairs
            PlayerStats stats = entry.getValue();                            // gets the value of the entry (PlayerStats)
            List<Shot> mappedPlayerShots = mapShotsTo2D(stats.shots(), courtData);
            mappedStatsById.put(entry.getKey(), new PlayerStats(stats.assists(), mappedPlayerShots)); // puts the key and the new PlayerStats with the mapped shots
        }
        return mappedStatsById;
    }

}
