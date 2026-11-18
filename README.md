# Notiva

Handschriftliche Notizen und PDF-Annotation als offline-fähige Web-App (PWA) –
optimiert für das **Lenovo Idea Tab Pro mit Lenovo Tab Pen Plus**.

تطبيق ملاحظات بخط اليد وقارئ PDF للدراسة، مصمَّم لجهاز Lenovo Idea Tab Pro مع قلم Pen Plus.

## Funktionen

- **Notizbücher** mit Vorlagen (leer, liniert, kariert, Punktraster, Cornell) und Papierfarben; bei arabischer Oberfläche liegt der Rand rechts
- **Import**: PDF, Bilder (mehrere Bilder → ein Dokument), Word (`.docx`, Text/Überschriften/Listen/Tabellen als Text), Text (`.txt`, `.md`)
- **Lesemodus**: nichts zeichnet, Stift und Finger blättern; Anpassen an Breite oder ganze Seite (passt sich beim Drehen an)
- **Teilen / Umwandeln**: PDF mit Notizen, PNG (aktuelle Seite oder alle), Textdatei, Original-PDF – über das Android-Teilen-Menü
- Werkzeuge: Stift (druckempfindlich), Textmarker, Radierer (ganze Striche oder präzise), Lasso, **Text** (Tastatur, Arabisch von rechts nach links)
- **Handschrift → Text** (nur Android-App): Schrift mit dem Lasso einkreisen → „In Text umwandeln“. On-Device-Erkennung (ML Kit) für Arabisch, Deutsch, Englisch; das Sprachmodell wird beim ersten Mal geladen
- **Automatisch begradigen**: Markierungen und Unterstreichungen werden zu sauberen geraden Linien (Handschrift bleibt unverändert)
- **Notizblase**: Stift 2 Sekunden auf ein Wort halten → eine Blase erscheint an der nächsten freien Stelle, mit Linie zum Wort; mit dem Stift hineinschreiben oder mit dem Textwerkzeug tippen
- Rückgängig/Wiederholen, Seiten hinzufügen/löschen, Arabisch / Deutsch / Englisch
- Läuft offline, Daten bleiben auf dem Gerät (IndexedDB)

## Studio-Funktionen

- **Design**: dunkles Navy-Design mit Cyan-Akzenten (helles Design in den Einstellungen), schwebende Werkzeugleiste, Live-Anzeige von Druck/Neigung des Pen Plus
- **Notiva-Notizbuch** (Seitenleiste): Ausschneide-Werkzeug – einen Satz unterstreichen schneidet den ganzen Absatz aus (Bild + Text aus dem PDF), oder einen Bereich umrahmen; eigene Notiz/Lernfrage pro Ausschnitt; Sprung zurück zur Quelle (auch dokumentübergreifend)
- **Lernkarten** aus den Ausschnitten (Frage = deine Notiz, Rückseite = Ausschnitt), **Zusammenfassung als PDF**
- **Sprachnotizen** (Mikrofon) mit Link zur aktuellen Seite
- **Intelligente Formen**: Linie (rastet bei 0/45/90° ein), Rechteck, Kreis/Ellipse, Dreieck, Vieleck
- **Laserpointer** (verblassende Spur, wird nicht gespeichert)
- **Lineal**: mit einem Finger verschieben, mit zwei drehen; Stift an der Kante = gerade Linie
- **Augenschonender Dunkelmodus** für PDFs/Bilder, **Doppelseiten-Ansicht**, Zoom- und Seitennavigation
- Textmarker wird bei geneigtem Stift breiter (Keilspitze), einstellbare **Druckkurve**

## Organisation, Sicherheit, Lernen

- **Sicherung**: eine `.notiva`-Datei (ältere `.alnai`-Sicherungen lassen sich weiter wiederherstellen) mit allem (Notizbücher, PDFs, Ausschnitte, Sprachnotizen, Vorlagen) über das Teilen-Menü in Drive o. Ä.; Wiederherstellen fügt hinzu und löscht nichts
- **Bibliothek**: Ordner, oben anheften, Farbmarken, Schlagwörter (Filter), Sortierung; **Volltextsuche** in PDFs und Ausschnitten (Arabisch ohne Taschkil)
- **Seitenübersicht**: Miniaturen, ziehen zum Umsortieren, duplizieren, einfügen, löschen; **Inhaltsverzeichnis** des PDFs, **Lesezeichen**; **Links im PDF** (Verweise springen, „Zurück zu Seite N“)
- **Geteilte Ansicht**: zweites Dokument daneben; Ausschnitte per „Ins Notizbuch einfügen“ in das Notizbuch daneben
- **Tabs**: zuletzt geöffnete Dokumente oben, direkt wechseln (max. 8)
- **Bilder auf Seiten** (unter der Tinte, verschieben/skalieren mit dem Auswahlwerkzeug), **eigene Vorlagen** (aus einer Seite, einem Bild oder der ersten PDF-Seite)
- **Checklisten** im Textwerkzeug (`[ ]` / `[x]`, mit dem Stift abhaken), **Erinnerungen** (einmal/täglich/wöchentlich, öffnen die Seite)
- **Scanner**: Seiten fotografieren; Seite wird erkannt, entzerrt (auch gewölbte Buchseiten über die Kantenmitten), Schatten entfernt; offenes Buch → zwei Seiten
- **Texterkennung (OCR)** offline auf dem Gerät (Tesseract, Arabisch/Deutsch/Englisch): gescannte Seiten werden durchsuchbar, Ausschnitte bekommen ihren Text, „als Text teilen“
- **PDF-Text ändern**: Zeile antippen → Original wird abgedeckt, korrigierter Text an seiner Stelle (Bildschirm und Export)
- **Beim Verlassen fragen**: Speichern / Nicht speichern / Abbrechen (abschaltbar)
- **Sperre** (optional) für Dokumente und Ordner per Fingerabdruck oder PIN; verschlüsselt keine Dateien

## Stift-Unterstützung (Pen Plus)

| Funktion | Umsetzung |
| --- | --- |
| Druckstufen | `PointerEvent.pressure`, geglättet, variable Strichbreite |
| Geringe Latenz | `desynchronized`-Canvas (Front-Buffer), `pointerrawupdate`, coalesced + predicted events |
| Handballenerkennung | Sobald ein Stift erkannt wird, zeichnet nur noch der Stift; Finger scrollen/zoomen. Große Kontaktflächen und Berührungen direkt nach Stiftkontakt werden ignoriert |
| Stifttasten | 1. Taste → Radierer, 2. Taste → Lasso (in den Einstellungen änderbar). Radierer-Ende (`buttons & 32`) radiert immer |
| Hover | Radierer-Vorschau, während der Stift über dem Display schwebt |
| Gesten | Zwei-Finger-Tipp = Rückgängig, Drei-Finger-Tipp = Wiederholen, Pinch-Zoom, Wischen mit Schwung |
| Stifttest | Einstellungen → „Stifttest“ zeigt live, was der Stift meldet (Typ, Druck, Neigung, Tasten) |

## Android-App (APK)

Jeder Push baut über `.github/workflows/android.yml` ein signiertes APK (Capacitor) und veröffentlicht es
unter **Releases** (`alnai-notes-v1.0.<Nr>`). Auf dem Tablet herunterladen, öffnen, „Aus dieser Quelle installieren“ erlauben.
Alle APKs sind mit demselben Schlüssel signiert – neue Versionen installieren sich als Update, die Notizen bleiben erhalten.
Der Schlüssel liegt **nicht** im Repository, sondern in den GitHub-Secrets (Settings → Secrets and variables → Actions):
`ANDROID_KEYSTORE_BASE64` (Keystore als Base64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Als Web-App installieren (Alternative)

1. Die veröffentlichte Seite (GitHub Pages, siehe unten) in **Chrome** auf dem Idea Tab Pro öffnen.
2. Menü ⋮ → **„App installieren“** bzw. „Zum Startbildschirm hinzufügen“.
3. Die App startet danach im Vollbild und funktioniert auch ohne Internet.

> Tipp: In den Lenovo-Einstellungen kann die Stifttaste mit einer Systemfunktion belegt sein.
> Falls die Taste in der App nichts tut, im Stifttest prüfen, was ankommt, und ggf. die Systembelegung deaktivieren.

## Entwicklung

```bash
npm install
npm run dev      # Dev-Server, im LAN erreichbar (--host) → direkt auf dem Tablet testen
npm test         # Unit-Tests (Geometrie: Radierer, Lasso)
npm run build    # Produktions-Build nach dist/ inkl. Service Worker
```

## Veröffentlichung

`.github/workflows/deploy.yml` baut und veröffentlicht bei jedem Push auf `main` auf GitHub Pages.
Einmalig nötig: Repository → Settings → Pages → Source: **GitHub Actions**.

## Aufbau

```
src/
  editor.ts          Seitenansicht, Kamera (Zoom/Pan), Stifteingabe, Werkzeuge, Undo
  clips.ts           Notiva-Notizbuch: Ausschnitte, Seitenleiste, Lernkarten, Sprachnotizen, Zusammenfassung
  ruler.ts           Lineal (verschieben, drehen, Kanten-Einrasten)
  ink.ts             Rendering der Striche und Seitenvorlagen
  geometry.ts        Treffertests, präzises Radieren, Lasso
  exportPdf.ts       PDF-Export mit pdf-lib
  pdf.ts             PDF-Rendering und Textzeilen/Absätze mit pdf.js
  library.ts         Bibliothek, neues Notizbuch, PDF-Import
  settingsDialog.ts  Einstellungen + Stifttest
  db.ts              Speicherung (IndexedDB)
```
