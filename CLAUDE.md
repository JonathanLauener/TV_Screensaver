# Minecraft Clock TV

Android-TV-Bildschirmschoner (fertig seit 2026-10-25) und eigener Startbildschirm für den Sony BRAVIA (192.168.178.23,
adb übers Netz). Er spielt `~/screensaver.webm` mit einer Uhr in Minecraft-Schrift. Zuerst `handoff.md` lesen (Stand,
Gerätefakten, nächste Schritte) und nach jedem größeren Schritt aktualisieren.

## Regeln
- **Der Fernseher wird geteilt.** Die Sessions für hianime-tv und 123moviesfree-tv installieren und testen ihre
  Wrapper-Apps (`tv.hianime.wrapper`, `tv.movies123free.wrapper`) auf demselben Gerät, und diese Apps stehen auf diesem
  Startbildschirm (`assets/home_apps.txt`). Vor Tastendrücken per adb prüfen, was im Vordergrund läuft
  (`dumpsys activity activities | grep ResumedActivity`) und ob jemand die Fernbedienung benutzt
  (`dumpsys power | grep mLastUserActivityTime`). Läuft die App einer anderen Session: ihr per ListAgents/SendMessage
  schreiben, auf ihr Go warten und Bescheid geben, wenn der Fernseher wieder frei ist. Nur installieren (`install.sh`)
  stört eine App im Vordergrund nicht.
- **Emulator statt Fernseher**, wenn der belegt ist: AVD `hianime_tv` in `~/.config/.android/avd` (`ANDROID_AVD_HOME`
  setzen). Mit Bash `run_in_background` starten (`nohup … &` stirbt mit dem Shell-Aufruf). Das Fenster muss auf
  Workspace 5 landen, nie auf dem aktuellen des Nutzers: Hyprland-Regel für die Klasse `Emulator` prüfen.
