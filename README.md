# Beagle

App Android nativa (**Kotlin + Jetpack Compose for TV**) para Google TV. Va en paralelo a HomeTV (Capacitor/WebView):
replicará su funcionalidad y, cuando llegue a la paridad, la sustituirá. Mientras tanto HomeTV sigue como está.

- Paquete: `es.locometopa.beagle` (distinto del de HomeTV: las dos pueden estar instaladas a la vez).
- Es un cliente del **mismo servidor de casa** que HomeTV. El servidor no se toca desde este repo: si hay que
  cambiarlo, se hace en el repo `hometv`.
- Estado: esqueleto con «Hola Beagle». Lo que falta está en [CHECKLIST.md](CHECKLIST.md).
- «Beagle» es un nombre provisional; solo está en `app/src/main/res/values/strings.xml` y en el banner.

## Estructura

| Archivo | Para qué |
|---|---|
| `app/src/main/java/es/locometopa/beagle/MainActivity.kt` | Entrada de la app (Compose for TV) |
| `app/build.gradle` | Dependencias, versión y firma |
| `version.txt` | Versión (0.1.0 → versionCode 100) |
| `herramientas/generar-imagenes.py` | Dibuja el icono y el banner |
| `.github/workflows/apk.yml` | Compilación y publicación de la APK |
| `CHECKLIST.md` | Funciones de HomeTV por replicar |

Versiones: Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin 2.0.21, Compose BOM 2024.12.01, TV Material 1.0.0, JDK 21.

## APK

Cada subida a `main` compila la APK y la publica en la rama `apk`:

    https://github.com/locometopa/beagle/raw/apk/Beagle.apk

Si la compilación falla, el registro queda en la rama `apk-error`. Los cambios que solo tocan archivos `.md` no compilan.

## Firma

Todas las APK van firmadas con la clave propia de Beagle para que cada una se instale encima de la anterior.
La clave **nunca está en el repo**: en GitHub son los secretos `KEYSTORE_B64` (el keystore en base64) y
`KEYSTORE_PASSWORD`; en el PC, el archivo `app/beagle.keystore` y la variable de entorno `KEYSTORE_PASSWORD`.
Sin ellos la APK sale firmada con la clave de pruebas.

## Compilar en el PC

Con Android Studio (abrir la carpeta) o `gradlew assembleRelease`.
