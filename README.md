# Skaner PDF – aplikacja na Androida

Skanuje papierowe dokumenty aparatem telefonu, poprawia ich wygląd (usuwa cienie, zażółcenie,
plamy i brud, wyostrza tekst) i zapisuje jako PDF w formacie A4. Gotowe pliki można
wydrukować, wysłać e-mailem albo udostępnić w dowolnej aplikacji.

## Funkcje

- **Skanowanie aparatem**: skaner Google ML Kit z automatycznym wykrywaniem krawędzi kartki,
  prostowaniem perspektywy, ręcznym kadrowaniem i obracaniem, usuwaniem palców i plam.
  Można zeskanować wiele stron naraz (do 50) albo zaimportować zdjęcia z galerii.
- **Poprawa wyglądu** (własny algorytm, `EnhancerCore.kt`), z podglądem przed zapisem:
  - *Kolor (czyszczenie)*: wybiela tło, usuwa cienie i zabrudzenia, a kolorowe elementy
    (pieczątki, logo) zostają zachowane,
  - *Odcienie szarości*,
  - *Czarno-biały*: progowanie adaptacyjne i usuwanie pojedynczych kropek; najmniejsze pliki,
    najlepszy do druku,
  - *Oryginał*,
  - przełącznik **Wyostrzanie** (maska nieostra).
- **Zapis do PDF**: strony A4 (pionowe lub poziome), ok. 255 dpi, zwykle 150–600 kB na stronę.
- **Lista dokumentów** z miniaturami: otwieranie, **drukowanie** (systemowe okno druku:
  drukarki Wi-Fi/Mopria), **e-mail** (PDF jako załącznik), **udostępnianie**, zapis kopii
  w dowolnym folderze (np. Pobrane, Dysk Google), zmiana nazwy, usuwanie.

## Wymagania

- Android 8.0 (API 26) lub nowszy, z Usługami Google Play. Przy pierwszym skanowaniu Usługi
  Google Play pobierają moduł skanera, więc potrzebny jest internet.
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
| `PreviewActivity.kt` / `PreviewViewModel.kt` | podgląd stron, wybór trybu, zapis z paskiem postępu |
| `EnhancerCore.kt` | algorytmy poprawy obrazu (czysty Kotlin, bez zależności od Androida) |
| `ImageEnhancer.kt` | dekodowanie i skalowanie zdjęć, konwersja Bitmap |
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
