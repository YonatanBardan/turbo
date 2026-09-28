# Basketball logic analyzer project

### This project main purpose is to analyze 3X3 streetball basketball game video that had been shot from a standing courtside persperctive. First goal is to be able to detect basketball logic correctly on a one man shootaround video. Second goal, is to manage basketball logic for a 1X1 streetball game. Third - 2X2 and laslty 3X3 game.

## Models used

### The project using two finetuned models:
     - Yolo26l for basketball detection (Classes: 0-ball, 1-hoop, 2-player).
     - Yolo26x for court keypoints detection (Classes - paint-left, paint-right, freethrow-left, freethrow-right)
### Both models trained based on different suitable 4000 images datasets
 (https://universe.roboflow.com/yonatans-workspace1/basketball-players-hoop-ball-detection2, https://universe.roboflow.com/yonatans-workspace1/court-detection-nd7jl)

---

## System pipline:
    1) Detection model extract detection boxes from the video that recieved and using 2 BOT-SORT custom  configs for better management of players occluisions and ball tracking. For any id the model generates we save "best" image crops and later transform those 15 images into vectors that will distinguish each player from another (Using: osnet_ain_x1_0 model). Later the list of detection box and roster vectors are transfer to the java backend - there another cleaned phase is happening merging similer player.

    2) FSM - the system tracks the ball state in each frame - ball losse, ball posssed, ball shot.
    When a shot is detected the system based on the parabolic trajectory of the ball based of the ball center coordinates and calculate the expected coordinate in the hoop Y coordinate level. then use the hoop width to assign a score for the ball trajectory made/miss. In addition, the system uses a visualProbability as the actual ball coordinate when crossing the hoop y coordinate for better average accurate result.

    3) Locating the shot coordinates on the court using homography matrix to simulate the shot on a 2D half court presenting. 

    4) Rendering the video with the merged id's, players stats and the 2D stats map.


## Phase 1 - Player Shootaround:


## Phase 2 - 1X1 game:

## License

This project is licensed under the **AGPL-3.0 License**.

### Third-Party Acknowledgements
This project modifies code from several open-source works.

* **BoT-SORT**: Multi-Pedestrian Tracking by Nir Aharon, Roy Orfaig, and Ben-Zion Bobrovsky. 
  Used for camera motion compensation and tracking. Licensed under the MIT License. 
  [GitHub Repository](https://github.com/NirAharon/BoT-SORT)
  
* **Torchreid (OSNet)**: Deep Learning Person Re-Identification by Kaiyang Zhou and Tao Xiang. 
  Used for player ReID vector extraction. Licensed under the MIT License.
  [GitHub Repository](https://github.com/KaiyangZhou/deep-person-reid)

* **Ultralytics YOLO**: by Ultralytics.
  Used for court and basketball detection. Licensed under the AGPL-3.0 License.
  [GitHub Repository](https://github.com/ultralytics/ultralytics)
