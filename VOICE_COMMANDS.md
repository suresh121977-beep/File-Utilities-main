# Voice control (File Manager Pro + File Explorer)

**Offline.** Voice recognition now runs inside the app with Vosk. It does not use the Google speech service and needs no internet while you use it.

## One-time build note
The build downloads the small English voice model (~40 MB) once and packs it into the APK
(`modules/VoiceKit/build.gradle.kts`, task `downloadVoskModel`). GitHub Actions does this automatically.
Building offline? Unzip `vosk-model-small-en-us-0.15` to `modules/VoiceKit/src/main/assets/model-en-us/` and add a file named `uuid` inside it.
The first time you tap the mic, the model is unpacked (a few seconds); after that it starts instantly.

## Spoken replies (like the Voice File Manager)
- Every answer is also read aloud while voice control is on, and the microphone pauses while the app speaks so it never hears itself.
- Tick a file and after a moment the app asks, out loud: "What can I do with N selected? Say your option." Say one word: copy, move, pickup, rename, zip, unzip, delete, backup, bookmark, share, copy path, open.
- `copy` / `move` -> say a folder number: it opens that folder and pastes straight in. Or open a folder yourself and say `paste`.
- `pickup` (repeat in many folders) -> open the destination -> `drop` -> pick numbers / `all` -> `drop`.
- `select jpg`, `select a` (first letter), `select report` tick matching items in the current list.
- The app picks the best of several recogniser guesses and fixes common mishearings (e.g. "sheep" -> zip, "past" -> paste).

## Using it
Tap the round mic button (bottom-right), allow the microphone. It keeps listening until you say **stop** or tap it again.
Every row shows a serial number.

| Say | What happens |
|---|---|
| `five` | folder 5 opens / file 5 is selected |
| `select two four six`, `select three to eight`, `select all`, `clear` | selection |
| `copy` / `move` | selection goes to the Pick & Drop basket |
| `pickup` | add selection to the basket |
| `drop` | (in the destination folder) shows the picked files with numbers |
| ...then `one three two` (any order) or `all` | choose what to drop |
| ...`move` or `copy` | choose the mode (move removes the file from its original folder) |
| ...`new folder photos` | creates the folder, goes into it and drops there |
| ...`drop` | does it. `drop all` or `drop two three` chooses and drops in one go. `cancel` closes. |
| `open`, `open five`, `open downloads` | open selected item / item 5 / item by name |
| `rename` -> say new name -> `confirm` | or `rename three to report` |
| `delete` -> `yes` (or `cancel`) | always asks first |
| `bookmark`, `zip`, `unzip`, `share`, `copy path` | act on the selection |
| `backup` | Pro: dated zip in /Backups. Explorer: copy into /Backup |
| `search invoice`, `search pdf`, `search dot jpg`, `clear search` | types into the search bar and filters |
| `new folder photos` | creates it |
| `back`, `home`, `refresh`, `help`, `stop` | navigation |

Any verb accepts numbers: `copy two five`, `delete four`, `share one to three`.
File Explorer home screen: say a number or a name (`open three`, `images`, `pick and drop`).
