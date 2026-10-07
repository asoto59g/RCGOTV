<img width="501" height="501" alt="Icono RCGO TV_ Control y Streaming" src="https://github.com/user-attachments/assets/854ac713-4f26-49b8-b455-5a3ec047c976" />


# RCGOTV para Android

Aplicación Android nativa en Kotlin para controlar televisores Google TV y Android TV compatibles con Android TV Remote v2 por la red local. Es una aplicación independiente; no ejecuta los scripts Python de Windows. El icono de la aplicación usa la imagen RCGOTV proporcionada.

## Funciones

- Descubrimiento de televisores mediante el servicio local de Android TV.
- Conexión manual por dirección IPv4.
- Emparejamiento inicial usando el PIN hexadecimal de seis caracteres que aparece en la TV.
- Certificado cliente y huella de TV guardados en el almacenamiento privado de la aplicación para reconectar.
- Lista de televisores guardados, organizada por subred Wi-Fi IPv4.
- Botones con iconos para encendido/reposo, navegación, inicio, atrás, volumen, canales, reproducción, entrada y menú; el botón central conserva «OK» para reconocerlo. Los iconos mantienen descripciones accesibles.
- Accesos visuales con distintivo e identificación para YouTube, Netflix, Prime Video, Spotify, Xuper TV y Stremio. Al tocar un icono se abre su ficha en Google Play en la TV; desde ahí se puede abrir la app instalada o instalarla.

El protocolo Android TV Remote no permite comprobar de forma fiable si una app está instalada para decidir entre abrirla e instalarla; por eso todos los accesos abren la ficha de Google Play y desde ahí se elige **Abrir** o **Instalar**. Esto evita enviar enlaces de protocolo no compatibles que algunas TV derivan a otras aplicaciones. Si la conexión remota se interrumpe, RCGOTV conserva la pantalla del mando y permite **Reconectar** sin volver al inicio de conexión.

La primera conexión requiere que la TV esté encendida, que ambos equipos estén en la misma red y que el usuario acepte el emparejamiento. Las conexiones guardadas pueden funcionar con la TV encendida y accesible; la activación desde el estado apagado depende del modelo, del servicio remoto y de la red, y no se garantiza.

## Requisitos para compilar

- Android Studio con JDK 17.
- Android SDK Platform 36 y Android Build Tools 36.x.
- Gradle 8.13 o Gradle Wrapper de esa versión.
- Acceso a Maven Central y Google Maven durante la primera compilación.
- Para publicar en Play Console, configura una clave de subida (upload key) y una cuenta de Google Play Console.

Las versiones se fijan en `app/build.gradle.kts` y `build.gradle.kts`. En Windows, compila una versión de depuración con el script:

```powershell
.\build-apk.ps1
```

El script compila una copia temporal en una ruta ASCII —necesario en Windows cuando la carpeta del proyecto tiene caracteres acentuados— y deja el APK instalable en:

```text
RCGOTV-debug.apk
```

También se puede abrir el proyecto directamente en Android Studio. Se incluye Gradle Wrapper 8.13.

## Preparar versión para Google Play

La app está configurada para Android 16 (API 36) y Google Play App Signing. Mantén `applicationId` como `com.abcgeomag.rcgotv` después de la primera publicación y aumenta `rcgotvVersionCode` en cada actualización. La clave de subida es independiente de la clave final con la que Google Play firma los APK distribuidos.

1. En PowerShell, con JDK 17 disponible, crea una clave de subida con `keytool`. Guarda el archivo fuera del proyecto y de carpetas sincronizadas; conserva una copia de seguridad segura. **No compartas ni subas el keystore o sus contraseñas al repositorio.**

   ```powershell
   keytool -genkeypair -v -keystore "$env:USERPROFILE\RCGOTV-upload.jks" -keyalg RSA -keysize 2048 -validity 10000 -alias rcgotv-upload
   ```

   `keytool` pedirá las contraseñas de forma interactiva. Protégelas y la copia de seguridad: sin la clave de subida no podrás firmar nuevas versiones con la misma identidad.
2. Ejecuta `.\build-play-release.ps1`. El script solicita ruta, alias y contraseñas de forma interactiva, ejecuta pruebas, compila el Android App Bundle firmado y verifica la firma. No guarda las contraseñas en el proyecto. El resultado es `RCGOTV-release.aab`.
3. En Play Console, crea la ficha de la app, acepta **Google Play App Signing**, sube el `.aab` a una prueba interna, completa la declaración de seguridad de datos, la clasificación de contenido, audiencia, acceso a la app y la información de soporte. Revisa los artefactos de pre-lanzamiento y prueba en dispositivos reales antes de producción.
4. Publica una política de privacidad revisada y accesible en una URL pública, con los datos correctos del responsable. Debe coincidir con las respuestas de Seguridad de datos de Play Console; no publiques un borrador sin completar.

Para una versión posterior, pasa un código de versión mayor y, si corresponde, un nombre nuevo: `.\build-play-release.ps1 -VersionName 1.0.1 -VersionCode 2`. Si se omiten, se usa la versión inicial del proyecto (`1.0.0`, código `1`).

La publicación final requiere la cuenta y las verificaciones del propietario en Play Console, una URL pública para la política y materiales de ficha (descripción, capturas de pantalla y gráfico promocional si se solicita). La compilación local no publica la app por sí sola.

## Instalar y usar

1. Instala el APK en un teléfono o tableta Android. Si se instala manualmente, permite la instalación desde esa fuente cuando Android lo solicite.
2. Conecta el dispositivo Android y la TV a la misma Wi-Fi/LAN y enciende la TV.
3. Abre **RCGOTV**, pulsa **Buscar televisores** y selecciona una TV detectada. Si no aparece, introduce su IPv4 y un nombre opcional.
4. En el emparejamiento inicial, acepta la solicitud en la TV, escribe el código de seis caracteres (incluye A–F si aparecen) y pulsa **Emparejar**.
5. Usa los botones táctiles; los televisores emparejados se guardan en el perfil de la subred local.

Los perfiles se separan por la dirección de red y longitud de prefijo IPv4 del Wi-Fi activo; no por el nombre SSID. Si el router cambia de subred, la app mostrará un perfil distinto. Los certificados y perfiles se guardan en las preferencias privadas de Android y se eliminan al desinstalar la app o borrar sus datos.

## Permisos y red

La aplicación usa permisos de red y descubrimiento local. No requiere Bluetooth, ADB, cuenta de usuario ni servicio en la nube. El router debe permitir tráfico entre los dispositivos; el aislamiento de clientes o una red de invitados puede impedir la detección y conexión. En ese caso, intenta la IP manual.

## Licencia y atribución

El proyecto conserva la licencia MIT y los avisos de copyright de la implementación Kotlin de protocolo Android TV Remote v2 usada como base. Consulta `LICENSE`.

## Seguridad del repositorio

Al publicar el código fuente, conserva `.gitignore` y agrega explícitamente solo los archivos del proyecto que quieras compartir. No publiques archivos `.jks`, `.keystore`, `.p12`, `.pfx`, `.key`, contraseñas, archivos `.env`, `local.properties`, APK/AAB compilados ni registros o capturas de pruebas. La clave de subida y su contraseña deben mantenerse fuera de GitHub.

El `.gitignore` excluye artefactos de compilación, material interno de pruebas y workflows de GitHub Actions, que no son necesarios para distribuir el código y deben revisarse antes de habilitarlos. Los borradores de privacidad están excluidos hasta que se revisen y completen. Antes de subir, confirma que no haya archivos ya rastreados o preparados que contengan información privada con `git status --short` y `git diff --cached --stat`.
