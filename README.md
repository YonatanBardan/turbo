# Basketball logic analyzer project

### This project main purpose is to analyze 3X3 streetball basketball game video that had been shot from a standing courtside persperctive. First goal is to be able to detect basketball logic correctly on a one man shootaround video. Second goal, is to manage basketball logic for a 1X1 streetball game. Third - 2X2 and laslty 3X3 game.

## Models used

### The project using two finetuned models:
- Yolo26l for basketball detection (Classes: 0-ball, 1-hoop, 2-player).
- Yolo26x for court keypoints detection (Classes - paint-left, paint-right, freethrow-left,        freethrow-right)
### Both models trained based on different suitable 3400, 4000 images datasets accordingly
- https://universe.roboflow.com/yonatans-workspace1/basketball-players-hoop-ball-detection2
- https://universe.roboflow.com/yonatans-workspace1/court-detection-nd7jl

---

## System pipline:

    1) Detection model extract detection boxes from a video and using 2 BOT-SORT custom  configs for better management of players occluisions and ball tracking. For any id the model generates we save "best" image crops and later transform those 15 images into vectors that will distinguish each player from another (Using: osnet_ain_x1_0 model). Later the list of detection box and roster vectors are transfer to the java backend - there another cleaned phase is happening merging similer player.

    2) FSM - the system tracks the ball state in each frame - ball losse, ball posssed, ball shot.
    When a shot is detected the system based on the parabolic trajectory of the ball based of the ball center coordinates and calculate the expected coordinate in the hoop Y coordinate level. then use the hoop width to assign a score for the ball trajectory made/miss. In addition, the system uses a visualProbability as the actual ball coordinate when crossing the hoop y coordinate for better average accurate result.

    3) Locating the shot coordinates on the court using homography matrix to map the shot coordinates on a 2D half court. 

    4) Rendering the video with the merged id's, players stats and the 2D stats map.


## Phase 1 - Player Shootaround:
IN this phase the main goals are to be able to detect miss/made shots and to map the shots' locations in the half court 2D plain. we can see that in the video below the court is well drained with nearly invisible lines. the model lack accuracy but manage throgh it.
In addition, the shot location set to be as of the last "possesd" frame which causing higher jumped shots to record a farther y location on the 2D map.

<table>
  <tr>
    <td align="center">
      <img width="350" height="400" alt="image" src="https://github.com/user-attachments/assets/976036f4-a242-43f2-9aca-5594153033bd" />
    </td>
    <td align="center">
      <img width="200" height="400" alt="Screenshot 2026-09-29 194556" src="https://github.com/user-attachments/assets/b0c08779-8703-4682-b5e1-ab9df487d723" />
    </td>
      <td>
          <img width="200" height="400" alt="Screenshot 2026-09-30 024759" src="https://github.com/user-attachments/assets/64f1ba7f-8e02-43db-9322-197f87df7745" />
      </td>
  </tr>
  <tr>
       <td align="center">
            <img width="350" height="400" alt="Screenshot 2026-09-30 022450" src="https://github.com/user-attachments/assets/8b944873-3bbb-4d53-895d-e876fb0a09d5" />
       </td>
    <td align="center">
       <img width="200" height="400" alt="Screenshot 2026-09-30 022522" src="https://github.com/user-attachments/assets/7b8dc883-1b59-4a91-b26b-aa7aa0626ed5" />
    </td>
      <td>
          <img width="200" height="400" alt="Screenshot 2026-09-30 024818" src="https://github.com/user-attachments/assets/7a2bdae0-20cc-47e8-9357-572cf6d74852" />
      </td>
   </tr>
</table>



After trailing 20 frames back from the last "possesd" frame before the ball changed its state to a loose state and avereging the shot coordinate we can see better result in the shot mapping, (if the shooter's velocity is high trails 8 frmaes back instead).

https://github.com/user-attachments/assets/83ff7f88-99b7-4f0e-8bff-6d4c8d37ca02

## Phase 2 - 1X1 game:
N/A
## Phase 3 - 2X2 game:
N/A
## Phase 4 - 3X3 game:
N/A

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
