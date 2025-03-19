![Telefyna](https://avventomedia.org/home/old/wp-content/uploads/2020/12/telefyna.png "Telefyna")

# Telefyna

---
The best simplest performing online stream & local file scheduling auto player for TV broadcasting.
___
## Infrastructure/Demo

---
[![Telefyna Demo](https://user-images.githubusercontent.com/29783151/126466086-2dc758df-0c20-403d-8b95-0a808243c47a.jpg)](https://www.youtube.com/watch?v=Oy5aN6MTcXM)

---
## Installation
* Download the [Coming Soon]() and install it, grant the app Storage permission and reload it if necessary

## Configurations
* User the [online configuration application](https://avventomedia.org/telefynaConfiguration/) to build and export your `config.json`
* Visit SDcard/drive or device storage without SDcard/drive and add a local `telefyna` folder playlist folder containing your local programs folder (`playlist`) and `config.json` file
* Ensure the telefyna app is granted storage permission in your permissions
* Telefyna logs audits onto `telefynaAudit` on the device storage
* Maintenance automatically runs at midnight each day and schedules programs for the starting day
* To tell Telefyna to reload any time you need to add `init.txt` file in `telefynaAudit` folder and it will reload on next program
* A started slot can't be updated even if you change programs, or schedule. changes can only affect the next schedule and following

### Device
* set `notificationsDisabled` to false to disable notifications
* set `automationDisabled` to true to disable and only use the first playlist

### Remote access
* If you want to access the filesystem remove, run an [FTP app like swiftp](https://github.com/avventoapps/avvento/releases/latest/download/swiftp.apk)
* You can use FTP clients like [FileZilla](https://filezilla-project.org/) or [RustDesk](https://rustdesk.com/) to upload both revised `config.json` and `playlist` folder/contents
* Alternatively you can auto sync local to device folder with something like [Syncthing](https://syncthing.net/)
* You can do the same `telefynaAudit` folder which is the internal device storage root path

### Playlist
* Ensure the device's Date and Timezone are set correctly
* The first playlist is the default  playlist and is used as a filler if nothing is available to play next or local folder is vacant
* The second playlist is a second default, it's a second filler choice and is played when `ONLINE` ones fail because of of internet issues
* If an internet connection is lost during a broadcast, the second playlist is defaulted to and if it's restored, the previous program will be restored too
* Both the above two default playlists must be maintained active, if any of them is local, better set resuming
* If you intend to use one playlist as default for both the first and second, make the second a schedule of the first
* `name` your playlist meaningfully
* `description` contains your explanation of about the playlist
* `days` of the week (`1-7`=Sun-Sat): if null or not defined, runs daily
* `dates` to schedule playlist for, date format must be `dd-MM-yyyy`
* Playlist `start` should be in format `HH:mm` eg `12:00` for mid-day, hours are in 24 hour
* `urlOrFolder`, stream url or local folder containing alphabetically ordered playlist folders
* For local playlists, if active and nothing is defined or completed, the default playlist will be played
* `type` can either be `ONLINE` (stream/default), or `LOCAL_SEQUENCED` (local ordered folder) or `LOCAL_RESUMING` (local resuming folder), or `LOCAL_RESUMING_SAME` (local resuming from same non completed program), or `LOCAL_RESUMING_NEXT` (local resuming from next program) or `LOCAL_RANDOMIZED` (local random folder)
* For `type`s `LOCAL_SEQUENCED`, `LOCAL_RANDOMIZED`
* Each playlist can load bumpers from 3 or less folders listed below
* You can define bumpers (ads, promos etc) to play as the `LOCAL_SEQUENCED` or `LOCAL_RANDOMIZED` playlist starts in `bumper` folder in a sub folder named same value as your playlist's `urlOrFolder`
* You can add folder named after `specialBumperFolder` value to tag any playlist to play bumpers located in `specialBumperFolder` folder 
* You can add `General` folder in `bumper` folder for general ads to play before all bumpers & programs
* All playlist are enabled by default, to disable one, set `active=false`
* `schedule` allows you to schedule a playlist defined up by its order/number/count (starts from 0) and manage separate/override `start` or `days` or `dates`
* A field left out in config is by default set to `null`
* Ensure to have a playlist completing/ending each local one else the default one will automatically be played

## Support
For any questions or queries, please receivers the support team at apps@avventomedia.org

---
# Issues

---
**Author:** AvventoProductions
<br>
**Last Updated:** `2025-03-19`
---
## To Do

---
- [ ] SRT support: [ExoPlayer Issue #8647](https://github.com/google/ExoPlayer/issues/8647)
- [ ] Support auto installation of config under resources if non-existent at first run
- [ ] Make Telefyna device specific; maintain a file with supported device IDs. If not granted, show video demo to help them get in touch at mail@avventomedia.org
- [ ] Investigate playing slot at 20:57. It played from start.
- [ ] Create a Telefyna LOCAL_RANDOMIZED special mode which loads folders and plays one at a time, or set logic for it
- [ ] Test if dates down the playlist overwrite the schedule
- [ ] Test playlist modification, etc.
- [ ] Test and fix midnight runner
- [ ] Test setupLocalPrograms: addedFirstItem
- [ ] Handle current play at switch not buffering video
- [ ] Add stop or change audit event
- [ ] Log every keypress
- [ ] Fix app relaunching, opening a new/duplicate instance rather than resuming
- [ ] Fix Swift bug: don't override, just replace (hack, skip by default, delete copy again) manually
- [ ] Player sometimes plays another in the background
- [ ] Investigate bumpers missing when loaded from scheduler
- [ ] Handle player idling on stream; resume play/seekTo
- [ ] Handle some unknown source error that makes the program switch; retry defaulting back to the program
- [ ] Add support for automatic drive syncing 
- [ ] Fix remote control key events listening not working
- [ ] Add background checker: if player is active and idle for 1 minute, play/switch again
- [ ] **Urgent; INVESTIGATE:** Connecting Bluetooth plays fillers? (17 Mar 21, 18:15). Also, player switches but plays only audio at 18:30.
- [ ] Create user-guide
- [ ] Support triggering reinitialization in the next program

### Links

- [ ] Support YouTube links and streams
- [ ] Look through existing TODOs
- [ ] Locally backup/download streaming content
- [ ] Build reports from audits
- [ ] Write tests
- [ ] Play video with different or additional audio/slave
- [ ] Read satellite channels and decoders as local playlists and streams
- [ ] Add a way to stream video as only audio (streaming audio only)
- [ ] Support streaming to HLS, Shoutcast, and Loudcast (use external streaming encoder, not supported)
- [ ] Ensure all wrong media files are skipped (blocked)
- [ ] Work on presentation approach
</font>

## Completed

---
## 2025
### January

---
- [x] Rename "clone" with "schedule"
- [x] Add "promos/sweepers/something" folder that starts the playout, usable for upnexts: "intros" folder containing another named by folder name: test symbolic
- [x] Schedule once per start (last), ignore the rest of the slots
- [x] Network listener: switches to second default when internet is off, and back if slot is still active
- [x] Add continuing play without seek to `LOCAL_RESUMING_NEXT`
- [x] Fork and add auto start using system prefs to FTP, send [PR](https://github.com/ppareit/swiftp/pull/163)
- [x] Support audit logs; mail them out
- [x] Work on "now playing" ORM (system preferences) to handle resuming local playlists at the next play, supporting daily periods and future dates
- [x] Add dates in addition to days
- [x] Remove repeats, use days for day
- [x] Reload configurations at midnight
- [x] Default back to the first playlist if the local playlist completes before end time
- [x] Fix `com.google.android.exoplayer2.source.BehindLiveWindowException` on HLS streaming
- [x] Fix playlist pending extra being null in broadcast
- [x] Play folder
- [x] Fix scheduling
- [x] Hide ExoPlayer buttons
- [x] ExoPlayer smoothly switches to another track
- [x] Support resuming from last played in playlist folder to next
- [x] Redo demo
- [x] Support logo, lower thirds, etc. (See: [ExoPlayer UI Components](https://exoplayer.dev/ui-components.html#customization))
- [x] Close ticker after a loop
- [x] Replay fillers if gone
- [x] Build a schedule GUI builder & viewer for `config.json`
- [x] Support RTMP format 
- [x] Overlay another layer on the video stream for ads, logos, gifs etc.
- [x] Support for Live & Repeat Watermarks (live is to show if turned on from the schedule and when u are on ONLINE playlist and others can show Repeat from the schedule if turned on) [#1](https://github.com/AvventoMedia/Telefyna-Kotlin-Application/pull/1)
- [x] Fix show Repeat watermark on Restart or Reboot reinitialization (look into onMediaItemTransition method)
- [x] Added show Repeat watermark on Preview schedule in scheduler 
- [x] Resize logo sizes on telefyna  `(2025-01-22)`
- [x] Add smooth and fade transitions between programs switches `(2025-01-23)`
- [x] Solve transition of the lower third to be smooth on start `(2025-01-24)`
- [x] Investigate Seek to program on restart (solved) `(2025-01-24)`
---
- [x] TelefynaAudit Folder not creating log files (fixed)
    #### API Level of the Device:
  * On older Android versions (API 28 and below), `Environment.getExternalStorageDirectory()` points to shared storage like `emulated/0/`.
  * On newer Android versions (API 29+), your app is confined to its private storage directory under `Android/data/<package>/`.
    This will always resolve to:
  * `emulated/0/Android/data/<package>/ `on newer devices.
    Request Legacy Access for Older Behavior: If you want to write to shared storage like `emulated/0/telefynaAudit` on `Android 10+`, add this to your `AndroidManifest.xml:
    xml`
  
    CopyEdit
  
    `<application
    android:requestLegacyExternalStorage="true" ... >
    </application>
    However, this will not work on Android 11+`.
---

- [x] Add Custom scroll ticker with time (able to show time and scrolling text) `(2025-01-24)` [#3](https://github.com/AvventoMedia/Telefyna-Kotlin-Application/pull/3)
- [x] Added fading mechanisms instead of abrupt cuts `(2025-01-24)` [#5](https://github.com/AvventoMedia/Telefyna-Kotlin-Application/pull/5)

### March

---
- [x] Support gifs in showing logo and watermarks `(2025-03-18)` [#21](https://github.com/AvventoMedia/Telefyna-Kotlin-Application/pull/21)