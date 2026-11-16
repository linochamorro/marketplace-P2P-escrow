# Decisiones técnicas de formato y visibilidad — EasyMarket

Fecha: 2026-11-16. Ámbito: índice público de decisiones técnicas que afectan la forma, la visibilidad y la documentación del repositorio. Este archivo es documentación técnica pública; no reproduce decisiones de proceso interno ni registros de ejecución.

## Propósito

Este índice hace permanentes y verificables las decisiones ya acordadas sobre qué se publica y cómo se documenta el repositorio, de modo que una persona que clone el proyecto pueda distinguir el contenido del producto de los artefactos de trabajo internos. No introduce decisiones nuevas ni cambia el comportamiento del sistema: solo registra las que ya están vigentes.

Formato de cada entrada: **contexto** (situación que motivó la decisión), **decisión** (qué se acordó) y **consecuencias** (qué implica para el árbol público).

---

## D-01 — Árbol público reducido (decisión G-6b)

**Contexto.** El repositorio contiene código de producto, configuración, documentación técnica y, Separadamente, artefactos de trabajo del ciclo de especificación, auditoría y verificación. Publicar todos los artefactos mezcla dos audiencias distintas y expone documentación de proceso que no describe el producto.

**Decisión.** El árbol público contiene únicamente producto (`backend/`, `frontend/`), pruebas permanentes (`tests/`), configuración declarada (plantillas de entorno, `.gitignore`, CI), `README.md`, documentación técnica (`docs/architecture/`, `docs/decisions/`) y capturas reales (`docs/images/`). Quedan fuera del público los documentos de proceso: reglas y estado operativo, contratos de agente, andamiaje de ejecución, reportes, la especificación y el plan de trabajo internos, y guías operativas internas. Esta decisión se conoce como G-6b.

**Consecuencias.** El README público no enlaza documentos de proceso. La exclusión se materializa en las reglas de `.gitignore`, no solo en una lista escrita a mano, de modo que la selección pueda verificarse sobre el árbol real.

---

## D-02 — Documentación técnica aprobada es versionable

**Contexto.** La regla global que ignora archivos Markdown para mantener fuera del público los reportes de proceso también excluía, sin querer, los documentos técnicos aprobados de `docs/architecture/` y `docs/decisions/`.

**Decisión.** Se conserva la exclusión global de Markdown para el contenido de proceso y se agregan excepciones de negación explícitas para `docs/architecture/*.md` y `docs/decisions/*.md`. La documentación técnica pública se versiona; los reportes internos siguen excluidos.

**Consecuencias.** Un documento técnico solo entra al árbol público si tiene contenido real. No se crean archivos ni carpetas vacías únicamente para satisfacer una excepción de seguimiento.

---

## D-03 — Licencia sin definir

**Contexto.** La licencia del proyecto de referencia no puede adoptarse por analogía: los proyectos de referencia tienen condiciones propias que no son las de EasyMarket.

**Decisión.** EasyMarket se publica sin licencia definida: todos los derechos reservados. No se añade MIT ni ninguna otra licencia por imitación de otros repositorios.

**Consecuencias.** El README declare esta condición de forma explícita. Cualquier cambio de licencia exige una decisión nueva y documentada, no una presunción.

---

## D-04 — Stack cerrado

**Contexto.** El producto está terminado y funcionando. Las versiones de Java, Spring Boot, Next.js, React y PostgreSQL, y el patrón de pagos con Stripe en modo sandbox, forman parte del estado acordado.

**Decisión.** No se reconstruye ni rediseña el producto. No se cambia la versión mayor de PostgreSQL, no se sustituye el backend ni el frontend por alternativas estáticas, y no se altera la integración de pagos. Los ajustes de este mantenimiento son de verificación, formato, documentación y publicación.

**Consecuencias.** La documentación describe el código existente. Las expectativas de despliegue externo se presentan como referencia no verificada, no como un resultado acreditado por este repositorio. Las decisiones de alcance de validación de servicios externos se registran en documentación interna de proceso y no se enlazan desde el árbol público.

---

## D-05 — Capturas reales, no referencias visuales

**Contexto.** Existen referencias visuales de drafting que incluyen código HTML y documentación de diseño. No son evidencia del producto en ejecución.

**Decisión.** La galería pública se compone únicamente de capturas funcionales reales obtenidas con Playwright CLI contra el entorno local aislado. Las referencias visuales de diseño quedan excluidas del árbol público y no se presentan como capturas.

**Consecuencias.** Mientras no existan capturas reales, el README indica que la galería se incorpora en una tarea posterior y no inventa rutas de imágenes. El directorio de imágenes documenta el plan de nombres y las reglas de privacidad de las capturas.

---

## D-06 — Configuración de referencia sin secretos literales

**Contexto.** Un archivo de referencia de PostgreSQL contenía originalmente una contraseña de desarrollo en texto plano como valor por defecto. Ese valor ya fue sustituido por una variable de entorno sin valor por defecto, por lo que el archivo no expone credenciales.

**Decisión.** El archivo de referencia de PostgreSQL pertenece al árbol público porque ya no contiene secretos literales. No se mantiene una rotación no justificada como bloqueo residual para excluir configuración necesaria que el README y la documentación siguen citando. Los valores reales permanecen solo en variables de entorno locales no versionadas.

**Consecuencias.** El README puede citar el archivo de referencia sin contradecir la selección pública. Cualquier credencial efectivamente expuesta o reutilizada requiere una decisión de rotación específica y documentada; el simple hecho de reconocer un formato de hash no demuestra exposición.

---

## Límites y trazabilidad

Este índice registra decisiones de formato y visibilidad. No contiene credenciales, rutas absolutas de estación de trabajo, identificadores de proceso, datos personales ni valores de secretos. Las decisiones de ejecución y verificación se documentan en los reportes de proceso, que quedan fuera del árbol público por D-01 y no se enlazan desde aquí.

Documentación pública relacionada:

- [README del repositorio](../../README.md)
- [Estructura de incorporación](../architecture/00-overview.md)
- [Galería de imágenes](../images/README.md)
