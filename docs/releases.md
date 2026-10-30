# Автоматичний APK у GitHub Releases

Кожен push у `main` запускає `.github/workflows/build-apk.yml`. Також можна
запустити його вручну через Actions → Build and release APK → Run workflow.
Після успішних unit-тестів і збірки підписаний release APK та SHA256SUMS.txt
з'являються у Releases і в артефактах Actions. Помилки публікації зупиняють workflow.
Номер запуску визначає versionCode та версію `1.0.<номер>`.
Повторний запуск оновлює файли того самого релізу.

## Одноразове налаштування підпису

У GitHub відкрийте Settings → Secrets and variables → Actions і додайте:

| Secret | Значення |
| --- | --- |
| ANDROID_KEYSTORE_BASE64 | Base64-вміст постійного JKS-ключа |
| ANDROID_STORE_PASSWORD | Пароль сховища ключів |
| ANDROID_KEY_PASSWORD | Пароль ключа з alias `upload` |

Ключ і паролі зберігайте в захищеній резервній копії поза Git.
Без того самого ключа Android не дозволить встановити оновлення поверх попередньої версії.
Не створюйте новий ключ під час кожного запуску. Не додавайте ключ або паролі в репозиторій.
Якщо в організації обмежені права GitHub Actions, дозвольте цьому workflow
`contents: write`, потрібне для GitHub Releases.

## Локальна збірка

Потрібні JDK 21, Gradle 9.3.1, Android SDK platform `android-36.1`,
build-tools `36.0.0`. Архів не містить Gradle Wrapper, тому використовується `gradle`.

Задайте KEYSTORE_PATH (абсолютний шлях до JKS), STORE_PASSWORD і KEY_PASSWORD
у середовищі, потім виконайте з кореня репозиторію:

```sh
gradle :app:testDebugUnitTest :app:assembleRelease --no-daemon
```

APK: `app/build/outputs/apk/release/app-release.apk`.
