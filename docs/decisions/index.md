# Decisiones técnicas de formato y visibilidad — EasyMarket

Este índice recoge las decisiones técnicas que afectan la forma, la visibilidad y la documentación del repositorio. No introduce cambios en el comportamiento del sistema.

Formato de cada entrada: **contexto** (situación que motiva la decisión), **decisión** (qué se acordó) y **consecuencias** (qué implica para el árbol del repositorio).

---

## D-01 — Repositorio orientado al producto

**Contexto.** El proyecto combina código de producto, configuración, documentación técnica y capturas funcionales. Publicar todos esos materiales mezclados diluye la información útil para quien clona el proyecto.

**Decisión.** El repositorio público contiene producto (`backend/`, `frontend/`), configuración declarada (plantillas de entorno, `.gitignore`, CI), `README.md`, documentación técnica (`docs/architecture/`, `docs/decisions/`) y capturas reales (`docs/images/`).

**Consecuencias.** El README enlaza únicamente documentación técnica existente. La selección de archivos se refleja en `.gitignore`, de modo que pueda verificarse sobre el árbol real.

---

## D-02 — Documentación técnica versionable

**Contexto.** La exclusión global de archivos Markdown para mantener fuera del árbol los documentos de trabajo también afectaba a los documentos técnicos de `docs/architecture/` y `docs/decisions/`.

**Decisión.** Se conserva la exclusión global y se agregan excepciones de negación explícitas para `docs/architecture/*.md` y `docs/decisions/*.md`. La documentación técnica se versiona.

**Consecuencias.** Un documento técnico solo entra al repositorio si tiene contenido real; no se crean archivos ni carpetas vacías únicamente para satisfacer una excepción.

---

## D-03 — Licencia sin definir

**Contexto.** La licencia de otros proyectos no puede adoptarse por analogía: sus condiciones no son las de EasyMarket.

**Decisión.** EasyMarket se publica sin licencia definida: todos los derechos reservados. No se añade MIT ni ninguna otra licencia por imitación de otros repositorios.

**Consecuencias.** El README declara esta condición de forma explícita. Cualquier cambio de licencia exige una decisión nueva y documentada.

---

## D-04 — Stack cerrado

**Contexto.** Las versiones de Java, Spring Boot, Next.js, React y PostgreSQL, junto con el patrón de pagos con Stripe en modo sandbox, forman parte del estado acordado del producto.

**Decisión.** La documentación describe el código existente. No se sustituye el backend ni el frontend por alternativas estáticas, no se cambia la versión mayor de PostgreSQL y no se altera la integración de pagos.

**Consecuencias.** Las expectativas de despliegue externo se presentan como referencia no verificada, no como un resultado acreditado por este repositorio.

---

## D-05 — Capturas reales, no referencias visuales

**Contexto.** Las maquetas y referencias visuales de diseño no son evidencia del producto en ejecución.

**Decisión.** La galería pública se compone únicamente de capturas funcionales reales obtenidas con Playwright CLI contra el entorno local aislado. Las referencias de diseño no se presentan como capturas.

**Consecuencias.** Las imágenes siguen reglas de formato, nombres en español en kebab-case, rutas relativas desde el README y privacidad (sin PII, credenciales ni tokens). La galería documenta su origen y los estados vacíos que muestran algunas vistas.

---

## D-06 — Configuración de referencia sin secretos literales

**Contexto.** Un archivo de referencia de PostgreSQL contenía originalmente una contraseña de desarrollo en texto plano como valor por defecto. Ese valor se sustituyó por una variable de entorno.

**Decisión.** `docker-compose.yml` pertenece al árbol público y usa la expresión Compose válida `${DB_PASSWORD:?DB_PASSWORD es requerida}` para la contraseña de PostgreSQL. El archivo no expone credenciales ni requiere un secreto literal.

**Consecuencias.** El README puede citar el archivo de referencia sin contradecir la selección pública. Los valores reales permanecen solo en variables de entorno locales no versionadas; reconocer el formato de un hash no demuestra exposición de una credencial.

---

## Límites

Este índice no contiene credenciales, rutas absolutas de estación de trabajo, identificadores de ejecución ni valores de secretos.

Documentación pública relacionada:

- [README del repositorio](../../README.md)
- [Arquitectura del sistema](../architecture/00-overview.md)
- [Galería de imágenes](../images/README.md)
