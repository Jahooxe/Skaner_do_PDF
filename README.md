# Skaner PDF – aplikacja na Androida

Skanuje papierowe dokumenty aparatem telefonu, poprawia ich wygląd (usuwa cienie, zażółcenie,
plamy i brud, wyostrza tekst) i zapisuje jako PDF w formacie A4. Gotowe pliki można
wydrukować, wysłać e-mailem albo udostępnić w dowolnej aplikacji.

## Funkcje

- **Własny skaner aparatem** (CameraX): na podglądzie na żywo widać zarys wykrytej kartki.
  Można zrobić wiele zdjęć z rzędu albo dodać zdjęcia z galerii.
- **W pełni automatyczne kadrowanie**: po zdjęciu strona jest od razu wykrywana, przycinana
  i prostowana. Detektor szuka brzegów kartki (krawędzie jasności i koloru, linie Hougha),
  wybiera najlepiej pasujący czworokąt i dopasowuje każdy bok z dokładnością do ułamka piksela
  (także wygięty). Na 140 zdjęciach testowych: 98% trafień, błąd rogów zwykle 1–2 px.
  Strony z niepewnym wykryciem są oznaczane „Sprawdź kadr”.
- **Korekta kadru tylko w razie potrzeby** (miniatura w aparacie lub „Kadruj” w podglądzie): 4 przeciągane rogi i 4 punkty na środkach krawędzi
  (do wyginania ramki przy zagiętej kartce), **lupa** z powiększeniem pod palcem, przyciski
  „Wykryj”, „Całość” i „Obróć”.
- **Prostowanie strony**: korekta perspektywy (homografia) i wygięć (płat Coonsa); proporcje
  bliskie A4 są dociągane do A4; resztki blatu przy brzegach są automatycznie zamalowywane.
- **Poprawa wyglądu** (`EnhancerCore.kt`): dwie siatki tła usuwają cienie, zażółcenie, plamy
  oraz **cienie zagięć i zmięć**; tryby *Kolor*, *Odcienie szarości*, *Czarno-biały*,
  *Oryginał*, przełącznik **Wyostrzanie**.
- **Zapis do PDF**: strony A4, ok. 255 dpi, zwykle 150–600 kB na stronę.
- **Lista dokumentów**: otwieranie, drukowanie, e-mail, udostępnianie, kopia w dowolnym
  folderze, zmiana nazwy, usuwanie.

Ograniczenie: drobne zmięcia wewnątrz kartki (przy prostych brzegach) są usuwane tylko
jako cienie; sam tekst może pozostać lekko falisty.

## Wymagania

- Android 8.0 (API 26) lub nowszy, z aparatem. Internet nie jest potrzebny.
- Do budowania: **Android Studio** (Ladybug 2024.2 lub nowsze) z JDK 17.

## Najprościej: APK budowany przez GitHub (bez Android Studio)

1. Załóż darmowe konto na github.com i utwórz repozytorium: **New repository**, nazwa np.
   `SkanerPDF`, zaznacz **Public** (wtedy APK pobierzesz bez logowania na telefonie), **Create**.
2. Na stronie pustego repozytorium kliknij **uploading an existing file**, przeciągnij całą
   *zawartość* folderu `SkanerPDF` (z podfolderami `app`, `gradle`, `.github`) i kliknij
   **Commit changes**.
   - Jeśli folder `.github` się nie przesłał (macOS domyślnie ukrywa foldery z kropką):
     **Add file → Create new file**, w nazwę wpisz `.github/workflows/build-apk.yml`,
     wklej zawartość tego pliku i kliknij **Commit changes**.
3. Zakładka **Actions**: budowanie startuje samo i trwa ok. 5–8 minut (zielony znaczek oznacza
   sukces).
4. Na telefonie otwórz `github.com/<twoja-nazwa>/SkanerPDF/releases`, stuknij
   **SkanerPDF.apk**, potem **Zainstaluj** (zezwól przeglądarce na instalowanie aplikacji).

Każda zmiana w plikach na GitHubie automatycznie tworzy nową wersję. Instaluje się ona na
starszej, bez utraty zapisanych skanów.

## Jak zbudować i zainstalować w Android Studio

1. Rozpakuj archiwum i w Android Studio wybierz **File → Open** i folder `SkanerPDF`.
2. Poczekaj na synchronizację Gradle (pobierze zależności).
3. Na telefonie włącz **Opcje programisty → Debugowanie USB** i podłącz go kablem.
4. Kliknij **Run ▶**. Aplikacja zainstaluje się i uruchomi.

Plik APK do samodzielnej instalacji: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
(plik trafi do `app/build/outputs/apk/debug/`).

Z wiersza poleceń (gdy zainstalowany jest Android SDK): `./gradlew assembleDebug`.

## Struktura kodu

| Plik | Rola |
|---|---|
| `MainActivity.kt` | lista PDF-ów, uruchamianie skanera, menu akcji |
| `ScanActivity.kt` / `FrameOverlayView.kt` | aparat, wykrywanie kartki na żywo, import z galerii |
| `CropActivity.kt` / `CropView.kt` | korekta kadru z lupą |
| `PageDetector.kt` | wykrywanie kartki (krawędzie, Hough, ocena kandydatów, dopasowanie boków) |
| `PageGeometry.kt` | kadr strony, prostowanie perspektywy i wygięć, czyszczenie brzegów |
| `ScanSession.kt` | strony w trakcie skanowania (zapisywane na dysku) |
| `PreviewActivity.kt` / `PreviewViewModel.kt` | podgląd stron, wybór trybu, zapis z paskiem postępu |
| `EnhancerCore.kt` | algorytmy poprawy obrazu (czysty Kotlin, bez zależności od Androida) |
| `PageProcessor.kt` | zapis zdjęć, obrót, renderowanie strony (prostowanie + poprawa) |
| `PdfWriter.kt` | zapis PDF z osadzonymi JPEG-ami (małe pliki, strona po stronie) |
| `ScanRepository.kt` | pliki w `files/scans/`, miniatury (PdfRenderer), nazwy |
| `DocumentActions.kt` | otwieranie, drukowanie, e-mail, udostępnianie (FileProvider) |

## Strojenie algorytmu

Parametry są na początku `EnhancerCore.kt`:
- `WHITE_POINT` (225): niższa wartość mocniej wybiela tło i usuwa więcej brudu,
  ale może rozjaśnić bladą treść (np. ołówek),
- `BLACK_POINT` (30) i `GAMMA` (1.3): kontrast i przyciemnienie tekstu,
- `SHARPEN_AMOUNT_X10` (8): siła wyostrzania,
- w `binarize()`: `85` to czułość progu dla trybu czarno-białego (wyższa wartość daje
  grubszy tekst).

Znane ograniczenie: duże, jasne i mało kolorowe zdjęcia na stronie mogą w trybach czyszczenia
zostać rozjaśnione, bo algorytm bierze je za tło. Wtedy wybierz tryb *Oryginał*.
