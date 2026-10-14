# Publicar una versión de TopoPhone

Las versiones se publican como APK firmado en [GitHub Releases](https://github.com/samuelsl27/TopoPhone/releases) (ADR-20, spec 001 RF-30 a RF-34). Todo lo hace `.github/workflows/release.yml` al subir una etiqueta `vX.Y.Z`. Este documento explica la preparación, que se hace una sola vez, y cómo publicar.

## 1. Clave de firma (una sola vez)

Todas las versiones deben firmarse con **la misma clave**. Si se pierde, los móviles que ya tienen TopoPhone no podrán actualizarse; habría que desinstalar y perder los datos locales. Guarda el fichero y sus contraseñas en tu gestor de contraseñas.

1. Genera el almacén de claves **fuera del repositorio** (PowerShell):
   ```powershell
   & "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\keytool.exe" -genkeypair `
     -keystore "$HOME\topophone-release.jks" -storetype PKCS12 -alias topophone `
     -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=TopoPhone, O=TopoPhone"
   ```
   keytool pide la contraseña del almacén. Con PKCS12, la contraseña de la clave es la misma.
2. Guarda en el gestor de contraseñas el fichero `topophone-release.jks`, la contraseña y el alias (`topophone`).
3. Registra la **huella pública** del certificado en el repositorio. No es secreta: sirve para que el CI compruebe que se firma siempre con esta clave (RF-34).
   ```powershell
   New-Item -ItemType Directory -Force release | Out-Null
   & "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\keytool.exe" -list -v `
     -keystore "$HOME\topophone-release.jks" -alias topophone | Select-String "SHA256:" |
     ForEach-Object { $_.Line.Trim() } | Out-File -Encoding ascii release\certificado-sha256.txt
   git add release/certificado-sha256.txt
   git commit -m "Huella del certificado de publicación"
   ```

## 2. Secretos de GitHub Actions (una sola vez)

Con [GitHub CLI](https://cli.github.com/) desde la raíz del repositorio:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\topophone-release.jks")) | gh secret set TOPOPHONE_KEYSTORE_BASE64
gh secret set TOPOPHONE_KEYSTORE_PASSWORD   # pide el valor
gh secret set TOPOPHONE_KEY_PASSWORD        # el mismo valor con PKCS12
gh secret set TOPOPHONE_KEY_ALIAS --body topophone
```
Los secretos solo llegan al trabajo de publicación. Las comprobaciones del CI, incluidas las de los pull requests de forks, no los usan.

## 3. Publicar una versión

1. Comprueba que `main` está en verde en Actions.
2. Elige la versión. La numeración empieza en `v0.0.1` y cada publicación sube el número: `v0.0.2`, `v0.0.3`…
3. Crea y sube la etiqueta:
   ```powershell
   git tag v0.0.1
   git push origin v0.0.1
   ```
4. El workflow "Publicación" hace estos pasos y, si alguno falla, no publica nada:
   1. Repite todas las comprobaciones del CI sobre el commit etiquetado (RF-30).
   2. Comprueba que la etiqueta es `vX.Y.Z` (RF-31) y mayor que la última publicada (RF-33).
   3. Comprueba que la firma está disponible (RF-32).
   4. Compila el APK release con R8, lo firma y verifica que el certificado coincide con `release/certificado-sha256.txt` (RF-34).
   5. Crea la release `vX.Y.Z` con `TopoPhone-X.Y.Z.apk` y notas generadas.

## 4. Instalar en el móvil

Descarga `TopoPhone-X.Y.Z.apk` desde Releases en el móvil y ábrelo. Android pedirá permiso para instalar apps de esa fuente. Las versiones siguientes se instalan encima, como actualización, y conservan los datos.

## Comprobaciones en local

```powershell
./gradlew :app:verifyReleaseTag -PreleaseTag=v0.0.1 --no-configuration-cache
./gradlew :app:assembleRelease          # sin variables TOPOPHONE_* sale sin firmar
```
