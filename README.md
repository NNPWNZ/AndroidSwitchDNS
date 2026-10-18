# Сборка APK приложения «Частный DNS» (Private DNS Toggle)

Архив `PrivateDnsToggle_AndroidProject.zip` содержит полностью настроенную структуру проекта Android Studio + workflow для бесплатной облачной сборки APK в **GitHub Actions** (без необходимости устанавливать Android Studio на компьютер).

## Способ 1: Скомпилировать APK в облаке через GitHub (даже с телефона за 2 минуты)

1. Создайте новый пустой репозиторий на [GitHub](https://github.com/new).
2. Распакуйте архив `PrivateDnsToggle_AndroidProject.zip` и загрузите все его папки и файлы (`app/`, `.github/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`) в репозиторий.
3. Перейдите на вкладку **Actions** в вашем репозитории:
   * Сборка `Build Android APK` запустится автоматически благодаря файлу `.github/workflows/build-apk.yml`.
   * Через ~2 минуты внизу страницы завершенной сборки в разделе **Artifacts** появится готовый архив `PrivateDnsToggle-debug-apk` с файлом **`app-debug.apk`**, который можно сразу установить на телефон.

## Способ 2: Сборка на ПК в Android Studio

1. Распакуйте `PrivateDnsToggle_AndroidProject.zip` в любую папку.
2. Откройте эту папку в **Android Studio** (**File -> Open**).
3. В верхнем меню выберите **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
4. Готовый файл появится по пути `app/build/outputs/apk/debug/app-debug.apk`.

## Однократная выдача разрешения после установки APK

После установки `app-debug.apk` на телефон выдайте разрешение на изменение системного Частного DNS один раз через ПК (ADB) или через приложение Shizuku:

```bash
adb shell pm grant com.example.privatednstoggle android.permission.WRITE_SECURE_SETTINGS
```
