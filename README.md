# Alnai Notes

Handschriftliche Notizen und PDF-Annotation als offline-fähige Web-App (PWA) –
optimiert für das **Lenovo Idea Tab Pro mit Lenovo Tab Pen Plus**.

تطبيق ملاحظات بخط اليد وتعليق على ملفات PDF، مصمَّم لجهاز Lenovo Idea Tab Pro مع قلم Pen Plus.

## Funktionen

- **Notizbücher** mit Vorlagen (leer, liniert, kariert, Punktraster, Cornell) und Papierfarben (weiß, creme, dunkel)
- **PDF importieren**, mit Stift und Textmarker annotieren, **als PDF exportieren** (Vektor-Tinte, teilbar über das Android-Teilen-Menü)
- Werkzeuge: Stift (druckempfindlich), Textmarker (liegt unter der Tinte), Radierer (ganze Striche oder präzise), Lasso (verschieben, duplizieren, umfärben, löschen)
- Rückgängig/Wiederholen, Seiten hinzufügen/löschen, Arabisch / Deutsch / Englisch
- Läuft komplett offline, Daten bleiben auf dem Gerät (IndexedDB)

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
Alle APKs sind mit demselben Schlüssel (`android/keystore/`) signiert – neue Versionen installieren sich als Update, die Notizen bleiben erhalten.

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
  ink.ts             Rendering der Striche und Seitenvorlagen
  geometry.ts        Treffertests, präzises Radieren, Lasso
  exportPdf.ts       PDF-Export mit pdf-lib
  pdf.ts             PDF-Rendering mit pdf.js
  library.ts         Bibliothek, neues Notizbuch, PDF-Import
  settingsDialog.ts  Einstellungen + Stifttest
  db.ts              Speicherung (IndexedDB)
```
