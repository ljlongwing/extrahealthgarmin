# 🔍 Отладка Tefal2Garmin в Android Studio

## 📋 Предварительные требования

### 1. Android Studio
- Установите Android Studio Arctic Fox или новее
- Убедитесь, что SDK установлен (API 21+)

### 2. Эмулятор или устройство
- Создайте AVD (Android Virtual Device) в Android Studio
- Или подключите физическое Android устройство с включенной отладкой

### 3. Debug APK
- Debug APK уже собран: `app/build/outputs/apk/debug/app-debug.apk`

## 🚀 Настройка отладки

### Шаг 1: Открыть проект в Android Studio
```bash
# Откройте Android Studio
# File → Open → Выберите папку tefal2garmin
```

### Шаг 2: Настроить эмулятор
1. В Android Studio: **Tools → Device Manager**
2. Создайте новый эмулятор:
   - **Device**: Pixel 4 или аналогичный
   - **System Image**: API 30+ (Android 11+)
   - **Storage**: Минимум 2GB
3. Запустите эмулятор

### Шаг 3: Установить приложение в режиме отладки
```bash
# В терминале (или через Android Studio)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 🐛 Процесс отладки

### Шаг 1: Запустить приложение в режиме отладки
1. В Android Studio откройте `MainActivity.kt`
2. Поставьте breakpoint на строке 197: `Log.d(TAG, "Handling intent: ${intent?.action}")`
3. Нажмите **Run → Debug 'app'**
4. Выберите запущенный эмулятор

### Шаг 2: Подготовить тестовый файл
```bash
# Скопировать тестовый файл в эмулятор
adb push test_files/Goodvibes-Dmitry-20251026231908-data.csv /sdcard/Download/
```

### Шаг 3: Тестирование загрузки файла

#### Вариант A: Через файловый менеджер
1. В эмуляторе откройте **Files** (или любой файловый менеджер)
2. Перейдите в **Downloads**
3. Долгое нажатие на `Goodvibes-Dmitry-20251026231908-data.csv`
4. Выберите **Share** → **Tefal2Garmin Sync**

#### Вариант B: Через ADB
```bash
# Отправить файл через intent
adb shell am start -a android.intent.action.SEND \
  -t "text/csv" \
  --eu android.intent.extra.STREAM file:///sdcard/Download/Goodvibes-Dmitry-20251026231908-data.csv \
  com.example.tefal2garmin/.MainActivity
```

### Шаг 4: Мониторинг логов

#### В Android Studio (Logcat)
1. Откройте **Logcat** tab
2. Фильтр: `package:com.example.tefal2garmin`
3. Ищите логи с тегом `MainActivity`

#### В терминале
```bash
# Мониторинг логов в реальном времени
adb logcat | grep -i "tefal2garmin\\|mainactivity\\|csv"
```

## 🔍 Возможные проблемы и решения

### Проблема 1: "Unsupported file type"
**Симптомы:** Приложение показывает ошибку "Unsupported file type"
**Решение:**
- Проверьте MIME тип файла: `file --mime-type test_files/Goodvibes-Dmitry-20251026231908-data.csv`
- Убедитесь, что файл передается с правильным MIME типом

### Проблема 2: "Content does not appear to be CSV data"
**Симптомы:** Файл проходит валидацию заголовков, но падает на проверке содержимого
**Решение:**
- Проверьте содержимое файла на наличие CSV структуры
- Убедитесь, что файл в кодировке UTF-8

### Проблема 3: Приложение крашится
**Симптомы:** Приложение закрывается при загрузке файла
**Решение:**
- Проверьте Logcat на исключения
- Убедитесь, что все разрешения предоставлены

### Проблема 4: Файл не отображается в Downloads
**Симптомы:** Файл не виден в файловом менеджере
**Решение:**
```bash
# Проверить, что файл скопирован
adb shell ls -la /sdcard/Download/

# Перезапустить Media Scanner
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/Goodvibes-Dmitry-20251026231908-data.csv
```

## 📊 Ключевые точки отладки

### В MainActivity.kt:
- **Строка 197**: `handleIntent()` - входная точка обработки файла
- **Строка 207**: Валидация intent
- **Строка 211**: Проверка MIME типа
- **Строка 244**: Обработка текстового содержимого
- **Строка 258**: Показ диалога ошибки

### В CsvValidator.kt:
- **validateHeaders()**: Проверка заголовков CSV
- **validateEncoding()**: Проверка кодировки

### В CsvReader.kt:
- **readCsvFile()**: Чтение файла

## 🛠️ Дополнительные инструменты отладки

### Device File Explorer
1. В Android Studio: **View → Tool Windows → Device File Explorer**
2. Перейдите в `/sdcard/Download/`
3. Проверьте наличие файла

### Memory Profiler
1. **Run → Profiler**
2. Мониторьте использование памяти при обработке больших файлов

### Network Profiler
1. Убедитесь, что приложение не делает сетевых запросов
2. Все должно обрабатываться локально

## 📝 Логи для анализа

### Успешная обработка:
```
MainActivity: Handling intent: android.intent.action.SEND
MainActivity: Valid intent received with MIME type: text/csv
CsvValidator: Header validation passed for format: primary
CsvReader: Successfully read 2 records from CSV
```

### Возможные ошибки:
```
MainActivity: Intent validation failed: Unsupported file type
CsvValidator: Header validation failed: Missing required headers
CsvReader: Failed to read CSV file: java.io.IOException
```

## 🎯 Следующие шаги после отладки

1. **Зафиксировать найденные проблемы**
2. **Добавить дополнительные логи** если нужно
3. **Исправить код** на основе найденных ошибок
4. **Пересобрать** и протестировать снова
5. **Создать финальный релиз** с исправлениями

## 📞 Полезные команды

```bash
# Переустановить приложение
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Очистить данные приложения
adb shell pm clear com.example.tefal2garmin

# Перезапустить приложение
adb shell am force-stop com.example.tefal2garmin
adb shell am start -n com.example.tefal2garmin/.MainActivity

# Получить информацию о приложении
adb shell dumpsys package com.example.tefal2garmin
```