# Aura Wall — запуск в Android Studio

1. File → Open → папка `AuraWall`. Студия сама скачает Gradle 8.9 (по gradle-wrapper.properties), AGP 8.7.2, Kotlin 2.0.21. Нужен JDK 17 (встроенный JBR подходит) и Android SDK 35.
2. Run ▶ `app` на устройстве (OnePlus / Xiaomi Pad 6 Pro, arm64) или эмуляторе x86_64 (API 29+, с GLES 3.0).
3. В приложении: вкладка «Система» → «Установить живые обои»; выдать «Поверх других окон» (нужно для диалога после 5 с удержания).
4. Вкладка «Модели» → «+ Импорт .glb» (свои модели). Без модели видны только градиентные обои.
5. LLM: положить Llama-style `.task` (MediaPipe LLM Inference) в
   `Android/data/com.aura.livewall/files/llm/`. Без файла работают запасные реплики характера.
