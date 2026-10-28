# Publicar IndentScope

Guía para publicar IndentScope en GitHub y en JetBrains Marketplace. Todos los comandos se ejecutan desde la raíz del
repositorio.

> La versión **1.3.0** ya fue publicada y aprobada en JetBrains Marketplace. Esta guía está preparada para la **1.4.0**:
> revisa que el número coincida con `gradle.properties` antes de usarla y no reutilices artefactos de versiones
> anteriores.

> El plugin está publicado en JetBrains Marketplace como **IndentScope** (ID `35438`, XML ID
> `com.access.IndentScope`) con licencia Apache 2.0. Las siguientes versiones se suben como **actualización**, no como
> plugin nuevo. El vendor y la organización se muestran como **NeoPlus**.

> Los cambios de la próxima versión se preparan sin commitear a propósito. Revisa `git diff` antes de confirmar nada.

## 1. Ficha de Marketplace

Estos son los valores que debe mostrar la ficha pública. Los campos se editan a mano en el panel de administración del
plugin; el repositorio no contiene credenciales para cambiarlos.

| Campo | Valor |
| --- | --- |
| Nombre | IndentScope |
| Plugin ID | `35438` |
| XML ID | `com.access.IndentScope` |
| Vendor / organización | NeoPlus |
| Licencia | Apache 2.0 |
| Precio | Free |
| Plugin homepage | `https://github.com/angelf015/IndentScope` |
| Source code | `https://github.com/angelf015/IndentScope` |
| Issue tracker | `https://github.com/angelf015/IndentScope/issues` |
| Documentation | `https://github.com/angelf015/IndentScope#readme` |
| Forum | Pendiente: habilitar GitHub Discussions (ver más abajo) |
| Tags | `Editor`, `Code Editing`, `User Interface`, `Productivity` |
| Compatibilidad | IntelliJ Platform 2025.2+ (build 252+) |
| Canal | Stable |

Los tags son los campos de categoría que Marketplace muestra encima del nombre. No existe un tag `Appearance`; los tags
válidos se eligen de la lista de Marketplace. Retira `Miscellaneous`, que es el único que tiene la ficha actual.

`plugin.xml` no acepta `<category>` para plugins de terceros. La categoría y los tags se configuran en el panel de
Marketplace, no en el XML. El elemento `<idea-plugin url="...">` sí se usa para la homepage del plugin y ya apunta al
repositorio.

En GitHub, actualiza también **About**:

- **Description:** `Rounded indent guides for the current scope in IntelliJ-based IDEs.`
- **Website:** `https://plugins.jetbrains.com/plugin/35438-indentscope`
- **Topics:** `intellij-plugin`, `intellij-platform`, `indent-guides`, `productivity`, `kotlin`, `ide`.

### Foro

GitHub Discussions no está habilitado en `angelf015/IndentScope`. Para completar el campo **Forum URL**:

1. Abre **Settings → General → Features** en GitHub y activa **Discussions**.
2. Usa `https://github.com/angelf015/IndentScope/discussions` como **Forum URL** en Marketplace.
3. Actualiza la sección **Support and links** de `README.md` y `README.es.md`.

Mientras Discussions siga deshabilitado, deja el campo vacío y usa el issue tracker para preguntas. No enlaces a una URL
que devuelva 404.

## 2. Assets y orden de publicación

Los assets viven en `docs/images/` y se usan tanto en los README como en la ficha de Marketplace:

| Orden | Archivo | Tamaño | Contenido |
| --- | --- | --- | --- |
| 1 | `docs/images/demo.gif` | 700×516, 226 fotogramas, 15,8 s | Demostración animada del cursor recorriendo ámbitos anidados. |
| 2 | `docs/images/method-scope.png` | 868×390 | Guía de un método desde la declaración hasta el cierre. |
| 3 | `docs/images/nested-scopes.png` | 868×680 | Método con bloques `while`, `if` y `else` anidados. |
| 4 | `docs/images/lambda-scope.png` | 666×386 | Lambda multilínea dentro de una llamada a `buildList`. |

Marketplace acepta GIF y recomienda capturas de al menos **1200×760 px**. Los assets actuales están por debajo de esa
recomendación, pero se pueden subir tal cual: Marketplace los escala. Para una ficha óptima, vuelve a capturarlos a
1200×760 con el mismo contenido; no los amplíes artificialmente porque el texto perdería nitidez.

Al subir los medios, usa este orden y añade un título corto a cada uno:

1. `demo.gif` — **Follow the caret through nested scopes**
2. `method-scope.png` — **Method scope**
3. `nested-scopes.png` — **Nested blocks**
4. `lambda-scope.png` — **Multi-line lambda**

### Iconos

El plugin usa la convención de JetBrains:

- `src/main/resources/META-INF/pluginIcon.svg`: icono para temas claros.
- `src/main/resources/META-INF/pluginIcon_dark.svg`: icono para temas oscuros.

El IDE elige la variante oscura automáticamente. Ambos archivos deben viajar dentro del JAR; compruébalo después de
compilar:

```bash
unzip -l build/libs/IndentScope-1.4.0.jar | grep pluginIcon
```

Marketplace usa `pluginIcon.svg` como icono principal de la ficha.

## 3. Descripciones

Marketplace toma la descripción de `<description>` en `plugin.xml` salvo que se haya editado a mano en el panel. La
descripción actual es bilingüe (inglés y español) e incluye enlaces a la documentación y al issue tracker. Si la ficha
tiene una descripción personalizada, sustitúyela por la nueva o añade el bloque en español.

### Inglés (corta)

```
Highlights the indentation guide of the current scope with a rounded bracket and restores the native guides on demand.
```

### Inglés (larga)

```
IndentScope draws a rounded bracket on the indent guide of the scope that contains the caret, from the line that opens
the scope through its closing }, ) or ].

It supports nested blocks, multi-line parentheses and brackets. Native indent guides are hidden only while the custom
guide is active, and each editor's original setting is restored when the feature is disabled. The guide also copes with
collapsed folds, soft-wrapped lines, multiple carets and HiDPI displays, and it can optionally appear in console, diff
and preview editors. Enclosing-scope guides are optional and dim each parent level, and scope changes can fade over
150 ms.

Customize scope types, stroke width, corner radius, native-guide behavior, auxiliary editor types and the guide color in
Settings → Tools → IndentScope. Global settings can be overridden per project with inheritance enabled by default. The
color is theme-aware and can also be changed in
Settings → Editor → Color Scheme → IndentScope. The status bar includes a quick toggle, and the UI is available in
English and Spanish.

Toggle it with Ctrl+Alt+G (Cmd+Option+G on macOS), from View → Appearance → IndentScope Guide, through the status-bar
widget, or with the stable IndentScope.ToggleGuide action ID in IdeaVim.

Requires IntelliJ Platform 2025.2 (build 252) or newer. Verified with IntelliJ IDEA 2025.2.6.3, 2025.3.6.1, 2026.1.5,
2026.2.3 and Android Studio 2026.2.1.8.

Documentation: https://github.com/angelf015/IndentScope#readme
Issues: https://github.com/angelf015/IndentScope/issues
```

### Español (corta)

```
Resalta con un corchete redondeado la guía de indentación del ámbito actual y restaura las guías nativas al desactivarla.
```

### Español (larga)

```
IndentScope dibuja un corchete redondeado sobre la guía de indentación del ámbito que contiene el cursor, desde la línea
que abre el ámbito hasta su cierre }, ) o ].

Admite bloques anidados, paréntesis y corchetes de varias líneas. Las guías de indentación nativas se ocultan solo
mientras la guía personalizada está activa, y la configuración original de cada editor se restaura al desactivar la
función. La guía también responde a pliegues contraídos, líneas ajustadas, varios cursores y pantallas HiDPI, y puede
aparecer opcionalmente en editores de consola, de diferencias y de vista previa. Las guías de ámbitos contenedores son
opcionales y atenúan cada nivel padre, y los cambios de ámbito pueden fundirse en 150 ms.

Configura los tipos de ámbito, el grosor, el radio, el comportamiento de las guías nativas, los tipos de editor
auxiliares y el color de la guía en Ajustes → Herramientas → IndentScope. La configuración global se puede sustituir por
proyecto, con la herencia activada por defecto. El color se adapta al tema y también se puede cambiar en
Ajustes → Editor → Esquema de colores → IndentScope. La barra de estado incluye un interruptor rápido y la interfaz está
disponible en inglés y español.

Actívala o desactívala con Ctrl+Alt+G (Cmd+Option+G en macOS), desde Ver → Apariencia → Guía de IndentScope, mediante el
widget de la barra de estado o con el ID de acción estable IndentScope.ToggleGuide en IdeaVim.

Requiere IntelliJ Platform 2025.2 (build 252) o posterior. Verificado con IntelliJ IDEA 2025.2.6.3, 2025.3.6.1,
2026.1.5, 2026.2.3 y Android Studio 2026.2.1.8.

Documentación: https://github.com/angelf015/IndentScope/blob/main/README.es.md
Incidencias: https://github.com/angelf015/IndentScope/issues
```

## 4. Comprobar el build

Requiere JDK 21 (el build declara el toolchain explícitamente).

```bash
./gradlew clean build buildPlugin verifyPluginStructure ktlintCheck
./gradlew koverHtmlReport koverVerify
./gradlew verifyPlugin
```

El build ejecuta **120 tests** (82 de plataforma/integración, 33 puros, 3 de rendimiento y 2 de bundles) y ktlint.
Resultado esperado de `verifyPlugin`:

```
IC-252.28539.97 (IntelliJ IDEA 2025.2.6.3): Compatible
IU-253.33813.55 (IntelliJ IDEA 2025.3.6.1): Compatible
IU-261.27258.48 (IntelliJ IDEA 2026.1.5): Compatible
IU-262.10968.63 (IntelliJ IDEA 2026.2.3): Compatible
AI-262.9437.185.2621.16467767 (Android Studio 2026.2.1.8): Compatible
Dynamic Plugin Eligibility: Plugin can probably be enabled or disabled without IDE restart
```

La línea de Android Studio solo aparece al ejecutar con `-PverifyAndroidStudio`.

Para incluir Android Studio (descarga un instalador de ~1,5 GB):

```bash
./gradlew verifyPlugin -PverifyAndroidStudio
```

El informe de cobertura queda en `build/reports/kover/html/index.html`. La cobertura de líneas actual es del **88,7 %**
y `koverVerify` falla el build si baja del 80 %.

> Pueden aparecer 3 avisos de API obsoleta: la creación de `TextAttributesKey` con atributos por defecto y el puente
> generado por Kotlin para `StatusBarWidget.getPresentation()`. Son avisos, no incompatibilidades.

> Esa última línea evalúa el código del plugin y no tiene en cuenta `require-restart="true"`. El IDE sí lee el atributo
> (`PluginDescriptor.isRequireRestart`) y fuerza el reinicio al instalar, actualizar o desinstalar.

El ZIP se genera en:

```
build/distributions/IndentScope-1.4.0.zip
```

> `buildPlugin` sobrescribe ese archivo. Si conservas el ZIP de una versión ya publicada, copia el artefacto y su hash
> antes de volver a compilar. No subas dos artefactos con la misma versión.

Antes de subirlo, pruébalo con **Settings → Plugins → Install Plugin from Disk** en una instancia limpia de IntelliJ IDEA
2025.2.6.3 o 2026.2.3. Comprueba también que el JAR incluye los dos iconos y que `docs/images/` **no** se empaqueta.

## 5. Revisar versión y changelog

La versión se define en `gradle.properties` y debe coincidir con la sección superior de `CHANGELOG.md`:

```properties
version=1.4.0
```

El changelog se inserta automáticamente en `plugin.xml` como `<change-notes>` durante `patchPluginXml`. No publiques dos
artefactos con la misma versión: JetBrains Marketplace rechaza la segunda subida.

## 6. Firmar el plugin (recomendado)

La firma evita el aviso de plugin no firmado al instalar. Es opcional para plugins gratuitos, pero recomendable.

Genera el certificado fuera del repositorio y no lo subas nunca a Git:

```bash
mkdir -p ~/keystores/IndentScope
chmod 700 ~/keystores/IndentScope
cd ~/keystores/IndentScope

openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
chmod 600 private_encrypted.pem
openssl req -key private_encrypted.pem -new -x509 -sha256 -days 3650 \
  -out chain.crt -subj "/CN=IndentScope/O=NeoPlus"
```

La clave permanece cifrada; no hace falta descifrarla en disco. El plugin Gradle lee estas variables de entorno
automáticamente:

```bash
export CERTIFICATE_CHAIN="$(cat ~/keystores/IndentScope/chain.crt)"
export PRIVATE_KEY="$(cat ~/keystores/IndentScope/private_encrypted.pem)"
export PRIVATE_KEY_PASSWORD="$KEYJET"
```

`$KEYJET` es solo un ejemplo de variable: guarda la contraseña en tu gestor de secretos y nunca la escribas en el
repositorio ni en `gradle.properties`.

Firma y verifica:

```bash
cd /Users/arch/workspace/java/IndentScope
./gradlew signPlugin
./gradlew verifyPluginSignature
```

Cuando `signPlugin` está configurado, el ZIP firmado se genera junto al original con el sufijo `-signed`:

```
build/distributions/IndentScope-1.4.0-signed.zip
```

Ese es el archivo que debes subir al Marketplace. Si no quieres firmar todavía, sube el ZIP sin firmar; el Marketplace lo
acepta y los usuarios verán un aviso al instalar.

## 7. Publicar en GitHub

Revisa los cambios y crea el commit:

```bash
git status
git diff

# Añade solo los archivos de la release; no incluyas capturas locales como newIssue.png.
git add CHANGELOG.md PUBLISHING.md README.md README.es.md TODO.md gradle.properties
git commit -m "Release 1.4.0: performance, project settings and visual polish"
git push origin main
```

Crea la etiqueta y súbela:

```bash
git tag -a v1.4.0 -m "IndentScope 1.4.0"
git push origin v1.4.0
```

Después, en GitHub:

1. Abre **Releases → Draft a new release**.
2. Selecciona la etiqueta `v1.4.0`.
3. Título: `IndentScope 1.4.0`.
4. Pega la sección `[1.4.0]` de `CHANGELOG.md` como notas.
5. Adjunta el ZIP de `build/distributions/`.
6. Publica la release.

El workflow `.github/workflows/build.yml` compila, ejecuta los tests, ktlint y la cobertura en Ubuntu, macOS y Windows,
valida el wrapper de Gradle en cada push y pull request, y publica el ZIP y el informe de cobertura como artefactos. La
verificación de compatibilidad se ejecuta en tags `v*`, en cada push a `main`, semanalmente y bajo demanda; el job
programado incluye Android Studio.

## 8. Publicar en JetBrains Marketplace

La subida de una versión se hace manualmente:

1. Inicia sesión en [JetBrains Marketplace](https://plugins.jetbrains.com/author/me) con tu JetBrains Account.
2. Abre la página del plugin **IndentScope** y pulsa **Upload new version**.
3. Rellena el formulario de la versión:
   - **Version**: `1.4.0`
   - **Since build**: `252`
   - **Until build**: vacío (compatible con todas las versiones posteriores)
   - **Channel**: vacío (canal estable)
   - **Change notes**: copia la sección `[1.4.0]` de `CHANGELOG.md`
4. Sube el ZIP firmado (o el ZIP sin firmar si aún no firmas).
5. Envía la versión a revisión.

Aprovecha para revisar la ficha del plugin:

1. **General Information**: nombre, vendor **NeoPlus**, licencia Apache 2.0, precio Free.
2. **Links**:
   - Plugin homepage: `https://github.com/angelf015/IndentScope`
   - Source code: `https://github.com/angelf015/IndentScope`
   - Issue tracker: `https://github.com/angelf015/IndentScope/issues`
   - Documentation: `https://github.com/angelf015/IndentScope#readme`
   - Forum: pendiente de GitHub Discussions (ver sección 1).
3. **Tags**: `Editor`, `Code Editing`, `User Interface`, `Productivity`. Quita `Miscellaneous`.
4. **Media**: sube el GIF y las tres capturas en el orden de la sección 2.
5. **Description**: usa la descripción bilingüe de `plugin.xml` o pega los bloques de la sección 3.

La revisión de una versión nueva suele tardar menos que la publicación inicial, pero puede tardar uno o varios días.
Cuando la versión esté aprobada:

1. Comprueba `https://plugins.jetbrains.com/plugin/35438-indentscope`.
2. Verifica que esa URL aparece en `README.md`, `README.es.md` y en la sección **About** de GitHub.
3. Verifica los topics `intellij-plugin`, `intellij-platform`, `indent-guides`, `productivity`, `kotlin` e `ide`.

## 9. Checklist por versión

- [ ] Actualizar `version` en `gradle.properties` y la sección superior de `CHANGELOG.md`.
- [ ] Ejecutar `./gradlew clean build buildPlugin verifyPluginStructure ktlintCheck`.
- [ ] Ejecutar `./gradlew koverHtmlReport koverVerify` y comprobar que la cobertura no baja del 80 %.
- [ ] Ejecutar `./gradlew verifyPlugin` y, en releases, `./gradlew verifyPlugin -PverifyAndroidStudio`.
- [ ] Inspeccionar el ZIP: versión, change notes, `pluginIcon.svg`, `pluginIcon_dark.svg` y ausencia de `docs/images/`.
- [ ] Comprobar que el repositorio no incluye un `.idea/indentScopeProject.xml` con ajustes locales del proyecto.
- [ ] Firmar con `./gradlew signPlugin` si usas certificado.
- [ ] Actualizar capturas, GIF y README si cambió la interfaz.
- [ ] Crear commit, tag y release en GitHub.
- [ ] Subir el ZIP desde la página del plugin en Marketplace (**Upload new version**).
- [ ] Revisar descripción, media, tags y enlaces de la ficha.
- [ ] Comprobar la página publicada y los topics del repositorio.

La primera subida ya está hecha y las siguientes también pueden automatizarse con `publishPlugin` y un token de
Marketplace, pero este repositorio está preparado para que las subidas las hagas tú manualmente.
