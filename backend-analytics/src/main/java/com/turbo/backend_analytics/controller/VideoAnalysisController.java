package com.turbo.backend_analytics.controller;

import com.turbo.backend_analytics.dto.TrackingResponse;
import com.turbo.backend_analytics.service.AiAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;

@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoAnalysisController {

    private final AiAnalysisService aiAnalysisService;

    public VideoAnalysisController (AiAnalysisService aiAnalysisService){
        this.aiAnalysisService = aiAnalysisService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<TrackingResponse> analyzeVideoFile(@RequestParam("file") MultipartFile file){

        try {

            // - Allocating new space in memory for the uploaded video;
            File tempFile = File.createTempFile("upload_", "_" + file.getOriginalFilename());
            file.transferTo(tempFile);
            String AbsolutePath = tempFile.getAbsolutePath();

            // - Activating the analyze service and delete from memory the temp video;
            TrackingResponse response = aiAnalysisService.analyzeVideo(AbsolutePath);
            tempFile.delete();

            return ResponseEntity.ok(response);
        }

        catch (IOException e){
            return ResponseEntity.internalServerError().build();
        }
    }
}

