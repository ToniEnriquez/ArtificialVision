# Vision - See what's in front of you - without seeing it.
A simple app for you to have fun with and scan random things!

## 🧠 Features

- 📷 **Camera Viewfinder**: Live camera feed with a tap-to-scan button.
- 🧠 **AI-Powered Object Understanding**: Sends captured images to GPT-4 Turbo (with vision) for scene analysis.
- 🔊 **Text-to-Speech (TTS)**: Describes the scene using Android’s TTS engine.
- 🔁 **Repeat Button**: Replay the last description.
- 🛠️ Built with:
  - Java + Android Studio
  - CameraX & ML Kit
  - OpenAI GPT-4 Turbo Vision API
  - TextToSpeech API

## Getting Started

### 🔐 API Key Setup
1. Create a `local.properties` file in the root of the project.
2. Add your OpenAI API key like this: OPENAI_API_KEY=sk-xxxxxxxxx
3. Sync the project and rebuild.

### Prerequisites

- Android Studio Hedgehog or later
- OpenAI API key with access to `gpt-4-turbo` and vision input
- Internet access for API calls

