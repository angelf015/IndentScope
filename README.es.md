# IndentScope

[English](README.md) · **Español**

[![Build](https://github.com/angelf015/IndentScope/actions/workflows/build.yml/badge.svg)](https://github.com/angelf015/IndentScope/actions/workflows/build.yml)
[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/35438-indentscope.svg)](https://plugins.jetbrains.com/plugin/35438-indentscope)
[![Descargas](https://img.shields.io/jetbrains/plugin/d/35438-indentscope.svg)](https://plugins.jetbrains.com/plugin/35438-indentscope)
[![Licencia: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Plugin de la plataforma IntelliJ que resalta la guía de indentación del **ámbito actual** con un corchete redondeado y
oculta las guías de indentación nativas mientras está activo.

La guía empieza en la línea que abre el ámbito y termina en su cierre, por lo que funciona con bloques, paréntesis de
varias líneas y corchetes de varias líneas:

```kotlin
fun main() {
    someFunction(          // la guía empieza aquí
        firstArgument,
        secondArgument,
    )                      // la guía termina aquí
}
```

## Demostración

![Demostración de IndentScope: la guía redondeada sigue al cursor por ámbitos anidados](docs/images/demo.gif)

La demostración mueve el cursor por los ámbitos de un método `TicketResponse create(...)`. La guía redondeada sigue el
ámbito habilitado más interno que contiene el cursor, y las guías nativas permanecen ocultas mientras la guía
personalizada está activa.

## Capturas

<p align="center">
  <img src="docs/images/method-scope.png" alt="Guía redondeada alrededor del cuerpo de un método" width="700">
  <br>
  <em>Ámbito de método: la guía va desde la declaración hasta la llave de cierre.</em>
</p>

<p align="center">
  <img src="docs/images/nested-scopes.png" alt="Guía redondeada alrededor de un método con bloques while, if y else anidados" width="700">
  <br>
  <em>Bloques anidados: el ámbito seleccionado conserva su guía mientras los bloques internos mantienen su indentación.</em>
</p>

<p align="center">
  <img src="docs/images/lambda-scope.png" alt="Guía redondeada alrededor de una lambda multilínea dentro de una llamada a buildList" width="666">
  <br>
  <em>Lambda multilínea: la guía sigue un argumento lambda dentro de una llamada a <code>buildList</code>.</em>
</p>

## Características

- Guía de indentación redondeada para el ámbito que contiene el cursor.
- Ámbitos cerrados por `}`, `)` y `]`.
- Muestra la guía de clase en los DTO y en otras clases sin métodos. Las guías para ámbitos que empiezan en la columna
  0 son opcionales.
- Funciona con bloques anidados y llamadas o condiciones de varias líneas.
- Muestra ámbitos sin llaves de nivel superior, como los bloques `def` y `class` de Python.
- Muestra bloques vacíos de varias líneas; los bloques de una sola línea no dibujan guía vertical de forma intencionada.
- Ignora los corchetes que aparecen dentro de cadenas, comentarios, plantillas de cadena y cadenas sin procesar.
- Ancla las declaraciones de estilo Allman a la línea de declaración en lugar de a la línea del `{`.
- Mantiene el aspecto aprobado: color de acento, esquinas redondeadas y sin brazos horizontales en las líneas del cuerpo.
- Oculta las guías de indentación nativas solo mientras la guía personalizada está activa.
- Restaura la configuración de guías nativas de cada editor al desactivar la guía, incluso si se cambió desde el IDE
  mientras el plugin estaba activo.
- Los ámbitos opcionales sin llaves terminan en su última línea de cuerpo; nunca toman prestado el cierre de un bloque
  exterior.
- Gestiona de forma coherente pliegues contraídos, líneas ajustadas, varios cursores y pantallas HiDPI.
- Reutiliza el análisis del ámbito y el resaltador mientras el documento y el ámbito no cambian, lo que mantiene ágil la
  edición de archivos grandes.
- Los editores de consola, de diferencias y de vista previa son superficies opcionales.
- Panel de ajustes en **Ajustes → Herramientas → IndentScope** para el comportamiento y la apariencia.
- Color de la guía adaptado al tema y personalizable por esquema de colores.
- Widget en la barra de estado para activar o desactivar la guía rápidamente.
- Interfaz en inglés y español.

## Instalación

Desde el IDE:

1. Abre **Ajustes → Plugins → Marketplace**.
2. Busca **IndentScope** y pulsa **Instalar**.
3. Reinicia el IDE cuando se solicite. El reinicio es necesario para que el plugin se active.

También puedes abrir directamente la [página de Marketplace](https://plugins.jetbrains.com/plugin/35438-indentscope).

Desde un ZIP descargado:

1. Descarga `IndentScope-1.3.0.zip` desde la
   [página de versiones de Marketplace](https://plugins.jetbrains.com/plugin/35438-indentscope/versions) o desde la
   [página de versiones de GitHub](https://github.com/angelf015/IndentScope/releases) cuando haya una release publicada.
2. Abre **Ajustes → Plugins**, pulsa el icono de engranaje → **Instalar plugin desde el disco...** y selecciona el ZIP.
3. Reinicia el IDE cuando se solicite. El reinicio es necesario para que el plugin se active.

## Activar o desactivar la guía

La función se puede desactivar globalmente, por ejemplo durante una presentación:

| Acción | macOS | Windows / Linux |
| --- | --- | --- |
| Activar o desactivar la guía de IndentScope | `Cmd+Option+G` | `Ctrl+Alt+G` |

También está disponible en **Ver → Apariencia → Guía de IndentScope** y en **Buscar acción** (`Cmd+Shift+A` /
`Ctrl+Shift+A`).

- El estado se conserva entre reinicios del IDE.
- El cambio se aplica inmediatamente a todos los editores y proyectos abiertos.
- Los editores nuevos respetan el estado actual.
- Mientras está desactivada no se analiza ningún ámbito.
- Los atajos se pueden reasignar en **Ajustes → Keymap**.

La barra de estado también muestra un widget **IndentScope**: haz clic para activar o desactivar la guía, o escóndelo
desde la configuración de la barra de estado como cualquier otro widget.

## Ajustes

Abre **Ajustes → Herramientas → IndentScope** para personalizar el plugin:

| Ajuste | Valor predeterminado | Descripción |
| --- | --- | --- |
| Activar la guía de IndentScope | Activado | Interruptor global, equivalente a la acción y al widget de la barra de estado. |
| Mantener visibles las guías de indentación nativas | Desactivado | Dibuja la guía personalizada sin ocultar las guías de indentación del IDE. |
| Mostrar la guía solo en el editor activo | Desactivado | Útil con editores divididos: solo el panel con el foco muestra la guía personalizada. |
| Mostrar la guía para ámbitos que empiezan en la columna 0 | Desactivado | Dibuja guías para ámbitos cuyo inicio está en la columna 0, como DTO de nivel superior. |
| Bloques `{ }` | Activado | Habilita guías para ámbitos delimitados por llaves, incluidos los bloques de Python basados solo en indentación. |
| Paréntesis `( )` | Activado | Habilita guías para paréntesis de varias líneas. |
| Corchetes `[ ]` | Activado | Habilita guías para corchetes de varias líneas. |
| Grosor del trazo | 1,5 px | Grosor de la guía, de 1,0 a 4,0 px en pasos de 0,5 px. |
| Radio de las esquinas | 4 px | Redondeo de las esquinas superior e inferior, de 0 a 12 px. |
| Usar un color personalizado para la guía | Desactivado | Sustituye el color del tema por un color elegido en el panel de ajustes. |
| Editores de consola | Desactivado | Dibuja la guía en los editores de salida de consola. |
| Editores de diferencias | Desactivado | Dibuja la guía en los visores de diferencias. |
| Editores de vista previa | Desactivado | Dibuja la guía en editores de vista previa, como resultados de búsqueda o vistas previas de estilo de código. |

Si se desactiva un tipo de ámbito, la guía se repliega hacia fuera hasta el ámbito habilitado más cercano en lugar de
desaparecer. Al menos un tipo de ámbito debe permanecer activo. Los editores principales se incluyen siempre; los tipos
de editor auxiliares son independientes y están desactivados por defecto.

### Robustez visual

- **Pliegues contraídos:** cuando la línea que abre el ámbito o su línea de cierre queda oculta por un pliegue
  contraído, la guía se oculta. Un pliegue dentro del cuerpo mantiene la guía exterior, y al desplegarlo se restaura de
  inmediato.
- **Ajuste de línea:** el trazo vertical omite las filas de continuación cuyo texto empieza a su izquierda, por lo que no
  pinta sobre el contenido ajustado. La primera y la última fila conservan los brazos redondeados.
- **Varios cursores:** el cursor primario define el ámbito actual. Añadir o quitar cursores actualiza la guía.
- **HiDPI:** el grosor del trazo y el radio de las esquinas se expresan en píxeles lógicos. El editor ya escala su
  `Graphics2D`, así que aplicar de nuevo la escala de la pantalla duplicaría el tamaño.
- **Columna 0:** los ámbitos cuyo inicio no tiene espacios delante son opcionales mediante **Mostrar la guía para ámbitos
  que empiezan en la columna 0**. Cuando se activa, la guía es una línea vertical sin brazos que va desde justo debajo de
  la línea de apertura hasta justo encima de la línea de cierre, por lo que nunca cruza la declaración ni su llave. Para
  ámbitos sin corchete de cierre, como un `def` de Python de nivel superior, cubre la última línea del cuerpo.
- **Cambios externos:** si **Mostrar guías de indentación** cambia en el IDE mientras el plugin está activo, se vuelve a
  aplicar la política de guías nativas y se conserva la última preferencia para cuando el plugin se desactive.

### Color de la guía

Sin un color personalizado, la guía sigue el esquema de colores activo. Puedes cambiarla en
**Ajustes → Editor → Esquema de colores → IndentScope**, donde respeta el tema actual y sus variantes clara y oscura.
El orden de resolución es:

1. Color personalizado de **Ajustes → Herramientas → IndentScope**.
2. Color **Guía de IndentScope** del esquema de colores activo.
3. Color de guía de indentación seleccionada del esquema.
4. Color de guía de indentación normal del esquema.

## Idiomas

El plugin incluye un paquete base en inglés y una traducción al español. Todos los textos visibles siguen
automáticamente el idioma del IDE. La documentación completa en inglés está en [README.md](README.md).

## IdeaVim

El ID de la acción es estable, por lo que se puede asignar directamente desde `.ideavimrc`:

```vim
" Activar o desactivar con <leader>ig (el líder predeterminado es \)
nnoremap <leader>ig <Action>(IndentScope.ToggleGuide)
```

Para conservar el mismo atajo de macOS dentro de Vim:

```vim
nnoremap <D-A-g> <Action>(IndentScope.ToggleGuide)
```

Comandos útiles durante la configuración:

```vim
:actionlist IndentScope
:action IndentScope.ToggleGuide
:source ~/.ideavimrc
```

## Requisitos

- IntelliJ Platform 2025.2+ (build 252+), incluidas 2025.3 y 2026.x.
- Verificado con IntelliJ IDEA 2025.2.6.3, 2025.3.6.1, 2026.1.5, 2026.2.3 y Android Studio 2026.2.1.8.
- El plugin no depende de ningún plugin de lenguaje concreto.

## Limitaciones conocidas

- IndentScope dibuja una guía cada vez: el ámbito habilitado más interno que contiene el cursor primario. Los ámbitos
  padre no se dibujan simultáneamente y los cursores adicionales no generan guías adicionales.
- Los ámbitos que empiezan en la columna 0 son opcionales. Activa **Mostrar la guía para ámbitos que empiezan en la
  columna 0** si quieres que las declaraciones de nivel superior muestren guía.
- Un ámbito que abre y cierra en la misma línea no dibuja guía vertical de forma intencionada.
- Los ajustes son globales; todavía no se pueden sobrescribir por proyecto.
- La detección de ámbitos usa el emparejador de llaves del lenguaje cuando está disponible. Para texto plano y lenguajes
  sin emparejador, un analizador léxico de respaldo cubre las sintaxis habituales de cadenas y comentarios, pero puede no
  reconocer sintaxis exóticas.
- Los editores de consola, de diferencias y de vista previa son opcionales y están desactivados por defecto.
- El plugin declara `require-restart="true"`, por lo que instalarlo, actualizarlo o desinstalarlo requiere reiniciar el
  IDE.
- Los archivos generados muy grandes pueden superar los límites de seguridad del análisis y quedarse sin guía.

## Compilación

Requisitos: JDK 21. El wrapper de Gradle descarga el resto y la compilación declara explícitamente un toolchain de JDK 21.

```bash
./gradlew build buildPlugin verifyPluginStructure
```

La tarea `build` también ejecuta ktlint a través del ciclo de vida `check`. El ZIP instalable se genera en:

```
build/distributions/IndentScope-1.3.0.zip
```

Instálalo desde **Ajustes → Plugins → Instalar plugin desde el disco**.

IndentScope declara `require-restart="true"`, así que el IDE siempre pide un reinicio después de instalarlo,
actualizarlo o desinstalarlo. Reinicia cuando se solicite: la guía estará activa inmediatamente después.

Para ejecutar un IDE sandbox con el plugin instalado:

```bash
./gradlew runIde
```

Para comprobar la compatibilidad binaria con los IDE admitidos:

```bash
./gradlew verifyPlugin
```

Android Studio usa un instalador grande, por lo que su verificación local es opcional:

```bash
./gradlew verifyPlugin -PverifyAndroidStudio
```

Para generar el informe de cobertura:

```bash
./gradlew koverHtmlReport
./gradlew koverVerify
```

El informe HTML se escribe en `build/reports/kover/html/index.html`. `koverVerify` hace fallar la compilación si la
cobertura de líneas baja del 80 %.

## Pruebas

```bash
./gradlew test
```

Las pruebas de plataforma cubren el interruptor global, la restauración de las guías nativas, la persistencia, los
paréntesis y corchetes de varias líneas, los ámbitos sin llaves que no deben tomar prestado el cierre de un bloque
exterior, los ámbitos de Python de nivel superior, los bloques vacíos y de una sola línea, la indentación mixta, las
cadenas y comentarios con contenido similar a corchetes, las comillas invertidas, las comillas triples, los comentarios
anidados, las declaraciones de estilo Allman, la persistencia y migración de ajustes, el filtrado por tipo de ámbito
con repliegue hacia fuera, el panel de ajustes, la página de colores, el widget de la barra de estado, los ámbitos plegados, los
pliegues dentro del cuerpo, los cursores múltiples, el filtrado de editores auxiliares, los cambios externos de guías
nativas, los editores reales con ajuste de línea, el ciclo de vida de controladores y servicios, el registro de editores
en segundo plano, el registro concurrente y la guía opcional de columna 0 con su geometría vertical y una comprobación a
nivel de píxel de que evita ambas líneas de borde. Un conjunto de pruebas puras independiente cubre el detector de
ámbitos, la visibilidad de pliegues, el filtrado por tipo de editor y la segmentación de rutas con ajuste de línea sin
arrancar el IDE, y un conjunto de pruebas de rendimiento verifica la reutilización del índice y la resolución de la guía
en archivos de 80 000 a 100 000 líneas. Una prueba de paquetes de recursos comprueba los recursos en inglés y español. El
conjunto tiene actualmente **99 pruebas** (69 de plataforma/integración, 25 puras, 3 de rendimiento y 2 de paquetes de
recursos).

## CI y calidad

Cada push y cada pull request compila, prueba, analiza y empaqueta el plugin en Ubuntu, macOS y Windows. El flujo de
trabajo también valida el wrapper de Gradle y publica el ZIP del plugin y el informe de cobertura como artefactos. La
verificación de compatibilidad se ejecuta en cada push a `main`, en las etiquetas de versión, semanalmente y bajo
demanda; el trabajo programado también verifica Android Studio.

```bash
./gradlew ktlintCheck
./gradlew koverHtmlReport
./gradlew verifyPlugin -PverifyAndroidStudio
```

Las reglas de formato compartidas por IntelliJ IDEA y ktlint están en `.editorconfig`, y la configuración de CI está en
`.github/workflows/build.yml`.

## Estructura del proyecto

```
src/main/kotlin/
├── CurrentScopeIndentGuideFactoryListener.kt  Listener de fábrica de editores
├── CurrentScopeIndentGuideController.kt       Estado por editor, ciclo de vida y actualizaciones
├── ActiveIndentGuideRenderer.kt               Renderer del resaltador y geometría redondeada
├── ScopeAnalysis.kt                           Detector e índice de corchetes por instantánea del documento
├── BracketScanner.kt                          Escáner de corchetes y fallback de texto plano
├── TextBracketIndex.kt                        Índice léxico y de corchetes en una pasada
├── ScopeDetector.kt                           Resolución pura de ámbitos, mapa de líneas y modelo de guía
├── GuideRobustness.kt                         Ayudas de visibilidad para pliegues, editores y ajuste de línea
├── IndentScopeService.kt                      Servicio de aplicación: controladores y refresco
├── IndentScopeSettings.kt                     Ajustes persistidos, normalización y migración heredada
├── IndentScopeConfigurable.kt                 Panel Ajustes → Herramientas → IndentScope
├── IndentScopeColors.kt                       Clave de color y resolución según el tema
├── IndentScopeColorSettingsPage.kt            Página Ajustes → Editor → Esquema de colores
├── IndentScopeStatusBarWidgetFactory.kt       Fábrica del widget de la barra de estado
├── IndentScopeBundle.kt                       Acceso a los mensajes localizados
└── ToggleIndentScopeGuideAction.kt            Acción global Mostrar/Ocultar

src/main/resources/
├── META-INF/
│   ├── plugin.xml                             Registro del plugin
│   ├── pluginIcon.svg                         Icono del plugin (tema claro)
│   └── pluginIcon_dark.svg                    Icono del plugin (tema oscuro)
└── messages/
    ├── IndentScopeBundle.properties           Textos en inglés
    └── IndentScopeBundle_es.properties        Textos en español

docs/images/
├── demo.gif                                   Demostración animada usada por ambos README
├── method-scope.png                           Guía de ámbito de método
├── nested-scopes.png                          Bloques anidados
└── lambda-scope.png                           Lambda multilínea

src/test/kotlin/com/access/
├── IndentScopeToggleTest.kt                   Pruebas de plataforma e integración
├── ScopeDetectorTest.kt                       Pruebas puras del detector y la visibilidad
├── ScopePerformanceTest.kt                    Pruebas de reutilización del índice y archivos grandes
└── IndentScopeBundleTest.kt                   Pruebas de recursos en inglés y español
```

## Soporte y enlaces

- **Marketplace:** [IndentScope](https://plugins.jetbrains.com/plugin/35438-indentscope)
- **Documentación:** este README y [README.md](README.md)
- **Incidencias y solicitudes de funciones:** [GitHub Issues](https://github.com/angelf015/IndentScope/issues)
- **Versiones:** [GitHub Releases](https://github.com/angelf015/IndentScope/releases)
- **Registro de cambios:** [CHANGELOG.md](CHANGELOG.md)

Las preguntas son bienvenidas en el seguimiento de incidencias, igual que los informes de errores. Enlazaremos aquí un
foro dedicado cuando se active GitHub Discussions.

## Contribuir

Las incidencias y los pull requests son bienvenidos en
[github.com/angelf015/IndentScope](https://github.com/angelf015/IndentScope).

## Licencia

Publicado bajo la [Apache License 2.0](LICENSE).
