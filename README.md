# Simple RTSP Streamer

Android-App, die die Kamera des Handys als **RTSP-Stream mit minimaler Latenz** im lokalen Netzwerk bereitstellt –
funktioniert aber mit jedem RTSP-Client (GStreamer, ffmpeg, VLC …).

Das Handy arbeitet als **RTSP-Server**: VLC (bzw. dessen `rtspsrc`) verbindet sich mit dem Handy und zieht den Stream.

## Installation

1. Auf GitHub unter **Releases → „Latest build“** die Datei `SimpleRTSPStreamer.apk` herunterladen (direkt auf dem Handy im Browser möglich). Alternativ: **Actions → Build → Artifacts**.
2. Die APK auf dem Handy öffnen und die Installation erlauben („Aus dieser Quelle installieren“).
3. App starten, Kamera-Berechtigung erteilen, **Streaming starten** tippen.

Mindestens Android 8.0 (API 26). Entwickelt und ausgelegt für das Google Pixel 7, läuft aber auf jedem Gerät mit Camera2- und H.264-Hardware-Encoder.

## Einrichtung mit VLC

1. Handy und VLC-Rechner im **selben Netzwerk**
2. In der App wird die Stream-Adresse angezeigt, z. B. `rtsp://192.168.2.177:1945/`. Antippen kopiert sie.
3. In VLC als Videoquelle Netzwerkstream wählen und genau diese Adresse eintragen.

Standard-Port ist **1945**; er lässt sich in den Einstellungen ändern.
Der Pfad ist egal – `rtsp://IP:1945/`, `rtsp://IP:1945/live` usw. liefern denselben Stream.

### Hinweise zur Ausrichtung des Bildes

Der Stream wird **nicht gedreht** (das spart eine GPU-Stufe und damit Latenz). Halte das Handy im **Querformat mit der Kamera links**
(Kamera zeigt vom Betrachter weg, Ladebuchse rechts). Die App zeigt an, ob das Bild gerade richtig herum, auf dem Kopf oder gekippt ist.

## Was die App für Stabilität und geringe Latenz tut

- **Kamera → Hardware-Encoder direkt über eine Surface** (kein OpenGL, keine Kopien), CBR, keine B-Frames, Low-Latency-Hints des Encoders.
- **Eigener RTSP/RTP-Server** (H.264, RFC 6184): Pro Empfänger eine eigene Sendewarteschlange. Ein langsamer oder hängender Empfänger kann
  weder den Encoder noch andere Empfänger ausbremsen; wer nicht mitkommt, springt zum nächsten Keyframe, statt Verzögerung aufzubauen.
- **Keyframe jede Sekunde**, SPS/PPS in jedem Keyframe, und bei jedem neuen Empfänger wird sofort ein Keyframe erzeugt – der Stream startet ohne Wartezeit.
- **TCP-Interleaved (Standard) oder UDP**, RTCP-Sender-Reports für saubere Synchronisation.
- **Foreground-Service** mit CPU-Wakelock und Low-Latency-WLAN-Lock: läuft auch mit ausgeschaltetem Bildschirm.
- **Selbstheilung**: Wird die Kamera von einer anderen App belegt oder unterbrochen, hängt der Encoder oder liefert die Kamera keine Bilder mehr,
  wird die Pipeline automatisch neu aufgebaut, ohne dass der RTSP-Server oder verbundene Empfänger getrennt werden.
- Eingebautes **Diagnoseprotokoll** (Schaltfläche *Protokoll* → kopieren/teilen) für die Fehlersuche ohne Computer.

## Einstellungen

| Einstellung | Standard | Bemerkung |
|---|---|---|
| Kamera | Rückkamera (ID 0) | Zoom-Regler auf dem Hauptbildschirm (beim Pixel 7 inkl. Ultraweitwinkel ab 0,5×) |
| Auflösung / Bildrate | 1920×1080 / 30 fps | Nur Werte, die Kamera **und** Encoder wirklich unterstützen |
| Bitrate | 8 Mbit/s | CBR |
| H.264-Profil | Baseline | Geringste Latenz und kompatibelste Dekodierung |
| Keyframe-Intervall | 1 s | Kurz = schnelle Erholung nach Paketverlust |
| Port | 1945 | 1024–65535 |
| Übertragung | TCP | *Automatisch* richtet sich nach dem Empfänger, *UDP* hat die geringste Latenz, aber Bildfehler bei Paketverlust |

Für lange Sitzungen empfiehlt sich in den Einstellungen **Akkuoptimierung deaktivieren** und das Handy am Ladegerät zu betreiben.

## Fehlersuche

| Symptom in VLC | Ursache / Abhilfe |
|---|---|
| `Connection refused` / „Could not open resource“ | Streaming in der App nicht gestartet, falsche IP/Port, oder Handy und VLC nicht im selben Netz (Client-Isolation im WLAN-Router?) |
| Verbindung steht, aber „no data“ | Protokoll auf *TCP* lassen; Firewall zwischen den Geräten prüfen |
| Bild steht auf dem Kopf | Handy um 180° drehen (siehe Hinweis in der App) |
| Gelegentliche Bildfehler | Übertragung *TCP* verwenden, Bitrate senken, 5-GHz-WLAN oder Hotspot des Handys direkt nutzen |
| Stream bricht nach längerer Zeit ab | Akkuoptimierung deaktivieren; im Protokoll nachsehen, was die App meldet |

## Aus dem Quellcode bauen

Voraussetzungen: JDK 17, Android SDK (Plattform 35, Build-Tools 35.0.0).

```bash
./gradlew :rtsp:test           # Unit-Tests des RTSP/RTP-Kerns
./gradlew :app:assembleRelease # APK: app/build/outputs/apk/release/
```

GitHub Actions baut bei jedem Push die APK (Artifact `SimpleRTSPStreamer-apk`); bei einem Tag `v*` wird zusätzlich ein Release mit der APK veröffentlicht.

### Aufbau

```
rtsp/   Reiner Kotlin/JVM-Kern: RTSP-Server, H.264-RTP-Packetizer, SDP, RTCP (ohne Android-Abhängigkeiten, voll getestet)
app/    Android-App: Camera2 + MediaCodec Pipeline, Foreground-Service, UI
```

Der RTSP-Kern lässt sich ohne Android-SDK testen (`./gradlew -PrtspOnly :rtsp:test`) und gegen echte Clients prüfen:

```bash
./gradlew -PrtspOnly :rtsp:devServer -Pfile=clip.h264   # spielt eine H.264-Datei als Live-Stream auf Port 1945
gst-launch-1.0 rtspsrc location=rtsp://127.0.0.1:1945/ latency=0 ! rtph264depay ! h264parse ! avdec_h264 ! autovideosink
```

### Signatur

Die APK wird mit dem im Repository liegenden Schlüssel `keystore/release.jks` signiert. Dadurch lässt sich jede neue Version als Update über die
vorherige installieren.
