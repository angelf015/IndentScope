# Publicar IndentScope

Guía para publicar IndentScope en GitHub y en JetBrains Marketplace. Todos los comandos se ejecutan desde la raíz del
repositorio.

> La versión **1.3.0** ya fue publicada y aprobada en JetBrains Marketplace. Esta guía queda como referencia para la
> próxima versión: actualiza los números de versión de los ejemplos antes de usarla y no reutilices `1.3.0`.

> El plugin está publicado en JetBrains Marketplace como **IndentScope** (ID `35438`, XML ID
> `com.access.IndentScope`) con licencia Apache 2.0. Las siguientes versiones se suben como **actualización**, no como
> plugin nuevo. El vendor y la organización se muestran como **NeoPlus**.

> Los cambios de la próxima versión están sin commitear a propósito. Revisa `git diff` antes de confirmar nada.

## 1. Comprobar el build

Requiere JDK 21 (el build declara el toolchain explícitamente).

```bash
./gradlew clean build buildPlugin verifyPluginStructure ktlintCheck
./gradlew koverHtmlReport
./gradlew verifyPlugin
```

El build ejecuta **88 tests** (60 de plataforma/integración, 23 puros, 3 de rendimiento y 2 de bundles) y ktlint.
Resultado esperado de `verifyPlugin`:

```
IC-252.28539.97 (IntelliJ IDEA 2025.2.6.3): Compatible
IU-253.33813.55 (IntelliJ IDEA 2025.3.6.1): Compatible
IU-261.27258.48 (IntelliJ IDEA 2026.1.5): Compatible
IU-262.10968.63 (IntelliJ IDEA 2026.2.3): Compatible
Dynamic Plugin Eligibility: Plugin can probably be enabled or disabled without IDE restart
```

Para incluir Android Studio (descarga un instalador de ~1,5 GB):

```bash
./gradlew verifyPlugin -PverifyAndroidStudio
```

El informe de cobertura queda en `build/reports/kover/html/index.html`. La cobertura de líneas actual ronda el 88 % y
`koverVerify` falla el build si baja del 80 %.

> Pueden aparecer 3 avisos de API obsoleta: la creación de `TextAttributesKey` con atributos por defecto y el puente
> generado por Kotlin para `StatusBarWidget.getPresentation()`. Son avisos, no incompatibilidades.

> Esa última línea evalúa el código del plugin y no tiene en cuenta `require-restart="true"`. El IDE sí lee el atributo
> (`PluginDescriptor.isRequireRestart`) y fuerza el reinicio al instalar, actualizar o desinstalar.

El ZIP se genera en:

```
build/distributions/IndentScope-1.3.0.zip
```

Antes de subirlo, pruébalo con **Settings → Plugins → Install Plugin from Disk** en una instancia limpia de IntelliJ IDEA
2025.2.6.3 o 2026.2.3.

## 2. Revisar versión y changelog

La versión se define en `gradle.properties` y debe coincidir con la sección superior de `CHANGELOG.md`:

```properties
version=1.3.0
```

El changelog se inserta automáticamente en `plugin.xml` como `<change-notes>` durante `patchPluginXml`. No publiques dos
artefactos con la misma versión: JetBrains Marketplace rechaza la segunda subida.

## 3. Firmar el plugin (recomendado)

La firma evita el aviso de plugin no firmado al instalar. Es opcional para plugins gratuitos, pero recomendable.

Genera el certificado fuera del repositorio y no lo subas nunca a Git:

```bash
mkdir -p ~/keystores/IndentScope
cd ~/keystores/IndentScope

openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

El plugin Gradle lee estas variables de entorno automáticamente:

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD='la-contraseña-elegida'
```

Firma y verifica:

```bash
cd /Users/arch/workspace/java/IndentScope
./gradlew signPlugin
./gradlew verifyPluginSignature
```

Cuando `signPlugin` está configurado, el ZIP firmado se genera junto al original con el sufijo `-signed`:

```
build/distributions/IndentScope-1.3.0-signed.zip
```

Ese es el archivo que debes subir al Marketplace. Si no quieres firmar todavía, sube el ZIP sin firmar; el Marketplace lo
acepta y los usuarios verán un aviso al instalar.

## 4. Publicar en GitHub

Revisa los cambios y crea el commit:

```bash
git status
git diff

git add .
git commit -m "Release 1.3.0: settings, visual robustness and personalized guide"
git push origin main
```

Crea la etiqueta y súbela:

```bash
git tag -a v1.3.0 -m "IndentScope 1.3.0"
git push origin v1.3.0
```

Después, en GitHub:

1. Abre **Releases → Draft a new release**.
2. Selecciona la etiqueta `v1.3.0`.
3. Título: `IndentScope 1.3.0`.
4. Pega la sección `[1.3.0]` de `CHANGELOG.md` como notas.
5. Adjunta el ZIP de `build/distributions/`.
6. Publica la release.

El workflow `.github/workflows/build.yml` compila, ejecuta los tests, ktlint y la cobertura en Ubuntu, macOS y Windows,
valida el wrapper de Gradle en cada push y pull request, y publica el ZIP y el informe de cobertura como artefactos. La
verificación de compatibilidad se ejecuta en tags `v*`, en cada push a `main`, semanalmente y bajo demanda; el job
programado incluye Android Studio.

## 5. Publicar en JetBrains Marketplace

La subida de una versión se hace manualmente:

1. Inicia sesión en [JetBrains Marketplace](https://plugins.jetbrains.com/author/me) con tu JetBrains Account.
2. Abre la página del plugin **IndentScope** y pulsa **Upload new version**.
3. Rellena el formulario de la versión:
   - **Version**: `1.3.0`
   - **Since build**: `252`
   - **Until build**: vacío (compatible con todas las versiones posteriores)
   - **Channel**: vacío (canal estable)
   - **Change notes**: copia la sección `[1.3.0]` de `CHANGELOG.md`
4. Sube el ZIP firmado (o el ZIP sin firmar si aún no firmas).
5. Envía la versión a revisión.

Aprovecha para revisar la ficha del plugin, que ya existe:

- **Vendor / Organization**: NeoPlus (debe coincidir con `<vendor>` en `plugin.xml`)
- **License**: Apache 2.0 (debe coincidir con `LICENSE`)
- **Category**: Appearance (o Code editing, según las opciones disponibles)
- **Tags**: `indent guides`, `indentation`, `editor`, `appearance`, `productivity`
- **Source code**: `https://github.com/angelf015/IndentScope`
- **Issue tracker**: `https://github.com/angelf015/IndentScope/issues`
- **Pricing**: Free

Descripción corta sugerida:

```
Highlights the indentation guide of the current scope with a rounded bracket and restores the native guides on demand.
```

Descripción larga sugerida:

```
IndentScope draws a rounded bracket on the indent guide of the scope that contains the caret, from the line that opens
the scope through its closing }, ) or ].

It supports nested blocks, multi-line parentheses and brackets. Native indent guides are hidden only while the custom
guide is active, and each editor's original setting is restored when the feature is disabled. The guide also copes with
collapsed folds, soft-wrapped lines, multiple carets and HiDPI displays, and it can optionally appear in console, diff
and preview editors.

Customize scope types, stroke width, corner radius, native-guide behavior, auxiliary editor types and the guide color in
Settings → Tools → IndentScope. The color is theme-aware and can also be changed in
Settings → Editor → Color Scheme → IndentScope. The status bar includes a quick toggle, and the UI is available in
English and Spanish.

Toggle it with Ctrl+Alt+G (Cmd+Option+G on macOS), from View → Appearance → IndentScope Guide, through the status-bar
widget, or with the stable IndentScope.ToggleGuide action ID in IdeaVim.

Requires IntelliJ Platform 2025.2 (build 252) or newer. Verified with IntelliJ IDEA 2025.2.6.3, 2025.3.6.1, 2026.1.5,
2026.2.3 and Android Studio 2026.2.1.8.
```

La revisión de una versión nueva suele tardar menos que la publicación inicial, pero puede tardar uno o varios días.
Cuando la versión esté aprobada:

1. Comprueba `https://plugins.jetbrains.com/plugin/35438-indentscope`.
2. Añade esa URL al `README.md` y a la sección **About** del repositorio de GitHub.
3. Añade los topics `intellij-plugin`, `intellij-platform`, `indent-guides` y `productivity` al repositorio.

## 6. Actualizaciones futuras

Para cada versión nueva:

1. Sube `version` en `gradle.properties`.
2. Añade una sección nueva a `CHANGELOG.md`.
3. Ejecuta `./gradlew clean build buildPlugin verifyPluginStructure ktlintCheck` y `./gradlew verifyPlugin`.
4. Revisa la cobertura con `./gradlew koverHtmlReport` y, si es una release, verifica también Android Studio con
   `./gradlew verifyPlugin -PverifyAndroidStudio`.
5. Firma con `./gradlew signPlugin` si usas certificado.
6. Crea commit, tag y release en GitHub.
7. Sube el ZIP desde la página del plugin en Marketplace (**Upload new version**).

La primera subida ya está hecha y las siguientes también pueden automatizarse con `publishPlugin` y un token de
Marketplace, pero este repositorio está preparado para que las subidas las hagas tú manualmente.
