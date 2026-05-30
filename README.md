# 朗語 (Rougo Reader) 📖

**朗語 (Rougo)** is a native Android media player specifically designed for Japanese language immersion and shadowing. It combines powerful video/audio playback with a professional-grade dictionary engine and advanced feedback tools to help you master Japanese intonation and listening.

---

## ✨ Key Features

### 📚 Integrated Dictionary Engine
- **Automatic JMdict Setup**: Downloads and imports the latest JMdict English dictionary on first startup.
- **Deinflected Headwords**: Intelligent lookup that always shows you the dictionary form of a word, no matter how it was conjugated.
- **Grouped Results**: Consolidates multiple dictionary sources into a single, clean interface.
- **Structured Content**: Supports full HTML/CSS rendering for complex Yomitan-style glossaries.

### 📈 Professional Pitch Accent Support
- **Visual Diagrams**: High-fidelity pitch graphs perfectly aligned with every kana character.
- **Overline Indicators**: Traditional overline and drop-bar displays for quick intonation checks.
- **Dictionary Source Tagging**: Every pitch entry is tagged with its source dictionary (e.g., NHK, Kanjium).

### 🎤 Advanced Shadowing Tools
- **Waveform Comparison**: Visually compare your recorded voice against the original audio to perfect your timing and rhythm.
- **Noise Cancellation**: Toggleable DSP processing optimized for speech capture in noisy environments.
- **Session Backlog**: Automatically saves and groups your recordings for easy review and progress tracking.

### 📱 Modern User Experience
- **Optimized Player Layout**: Strict layout management ensuring subtitles and video remain clear even with controls visible.
- **Customizable Priority**: Reorder your installed dictionaries to set your own search priority.
- **System Navigation**: Full support for system back gestures and standard Android UI patterns.

---

## 📸 Screenshots

| Player & Subtitles | Dictionary Lookup | Pitch Accent Details |
|:---:|:---:|:---:|
| ![Player](media/screenshots/player.png) | ![Search](media/screenshots/search.png) | ![Pitch](media/screenshots/pitch.png) |

| Shadowing Backlog | Waveform Analysis | Dictionary Management |
|:---:|:---:|:---:|
| ![Backlog](media/screenshots/backlog.png) | ![Comparison](media/screenshots/waveform.png) | ![Settings](media/screenshots/settings.png) |

---

## 🚀 Installation

1. Go to the [Releases](https://github.com/kaihouguide/rougo/releases) page.
2. Download the APK corresponding to your device's architecture:
   - **`arm64-v8a`**: For most modern Android phones.
   - **`universal`**: If you are unsure which one to pick.
3. Install the APK on your device.
4. On first run, the app will automatically set up the JMdict dictionary for you.

---

## 🛠 Building from Source

### Prerequisites
- Android Studio Ladybug or newer.
- JDK 17.
- Android NDK (for native dictionary components).

### Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/kaihouguide/rougo.git
   ```
2. Open the project in Android Studio.
3. Sync Project with Gradle Files.
4. Build and Run.

---

## 📜 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
