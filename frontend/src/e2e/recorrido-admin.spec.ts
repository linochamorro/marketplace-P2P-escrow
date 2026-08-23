/**
 * Spec E2E — Recorrido completo de ADMIN (PHA07TSK07).
 *
 * <p>Verifica el flujo navegable completo del rol ADMIN contra los servicios
 * REALES del entorno local (PostgreSQL + backend dev + frontend + Stripe CLI
 * validados por el harness de PHA07TSK05), sin ningún mock de producción: no
 * se usa {@code page.route}, no se intercepta red y no se sustituye el
 * transporte de datos. Cada aserción sobre contenido renderizado ES la
 * verificación indirecta de la respuesta real del endpoint que la produce.</p>
 *
 * <p>Traza a la Story 13 (panel administrativo) y a plan.md Fase 7 (Entorno
 * E2E y Cuentas E2E, filas 533 y 539). El recorrido es: precondición con el
 * vendedor demo (crea una publicación pendiente real para tener qué moderar)
 * → login ADMIN con las credenciales del {@code .env} raíz (validación E2E
 * del gate 9 del harness: si la contraseña fuera inválida, el login falla y
 * el shell no muestra los 12 destinos del rol) → dashboard con las 6 métricas
 * reales → moderación de la publicación creada → CRUD real de categorías →
 * disputas y cuentas bloqueadas con estado vacío legítimo → aislamiento del
 * rol USUARIO (barrera SoloAdmin en rutas /admin).</p>
 *
 * <p>Orden estricto y serial: el flujo es secuencial y con estado (login
 * ADMIN → dashboard → moderación → catálogo → ...). Se usa
 * {@code test.describe.serial} con una única {@code Page} compartida creada
 * en {@code beforeAll} para conservar la cookie de sesión {@code jwt}
 * (httpOnly) y la navegación real entre pasos; cada paso es un {@code test}
 * del grupo serial. Otros spec files (preparación, home) corren en workers
 * separados (config {@code fullyParallel: true}); este archivo es
 * autocontenido y no depende del estado de otros specs.</p>
 *
 * <p>Credenciales ADMIN (decisión declarada en el Artifact): se leen del
 * {@code .env} raíz (ignorado por Git) porque {@code leerEnvArchivo} de
 * {@code entorno.ts} es privada del harness. El valor de la contraseña nunca
 * se imprime ni se loguea — solo se inyecta en el campo del formulario.</p>
 *
 * <p>Determinismo y re-ejecución (decisión de Lino 2026-08-16, vinculante):
 * la publicación y la categoría creadas llevan un sufijo {@code Date.now()}
 * único por corrida, de modo que cada ejecución trabaja sobre datos propios
 * y no colisiona con los de una corrida anterior. Las disputas y las cuentas
 * bloqueadas se aseveran en su estado vacío REAL (el seed NO crea
 * transacciones ni bloqueos por diseño, PHA06TSK08): aseverar esos textos
 * (que el componente solo renderiza tras un 200 con lista vacía) prueba un
 * GET exitoso real, no es un mock ni alcance nuevo.</p>
 *
 * <p>"Volumen en escrow" es DINÁMICO desde PHA07TSK08 (decisión de Lino
 * 2026-08-16 de reabrir TSK07, Loop L02): la suite de compra con escrow
 * ({@code compra-escrow.spec.ts}) crea transacciones {@code reservada}
 * REALES de S/ 99.99 por corrida (pago sandbox con webhook real), por lo
 * que el valor de esa métrica ya no es siempre {@code S/ 0.00}. El paso 3
 * la asevera con el patrón {@code ^S\/ \d+\.\d{2}$} — acepta BD limpia y
 * estado con reservadas, y exige soles bien formados en centavos
 * (principio 3 de constitution.md). Las otras 5 métricas del dashboard
 * siguen estáticas: TSK08 no crea disputas, no libera fondos ni finaliza
 * transacciones, el seed no crea bloqueos (PHA06TSK08) y este recorrido
 * no modifica ninguna de esas fuentes.</p>
 *
 * <p>Robustez ante compilación on-demand de Next.js en modo dev: el primer
 * acceso a una ruta aún no compilada dispara una recompilación del dev server
 * y el clic sobre un {@code Link} puede descartarse si la compilación coincide
 * con el envío del evento (fenómeno real observado en este entorno; ver
 * {@code navegarConReintento} en recorrido-usuario.spec.ts). Las navegaciones
 * por enlace del recorrido usan ese mismo helper.</p>
 */

import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { test, expect, type Locator, type Page } from '@playwright/test';

/** Directorio de este módulo: {@code <repo>/frontend/src/e2e}. */
const E2E_DIR = __dirname;

/** Directorio raíz del repositorio (donde vive el {@code .env} con las credenciales ADMIN). */
const REPO_ROOT = resolve(E2E_DIR, '..', '..', '..');

/** Archivo de secretos locales de la raíz del repositorio (ignorado por Git). */
const ENV_RAIZ = join(REPO_ROOT, '.env');

/** Descripción única de la publicación creada en la precondición de este recorrido. */
const descripcionPublicacion = `Publicación E2E TSK07 ${Date.now()}`;

/** Precio en soles ingresado en la UI (se convierte a centavos en la frontera HTTP). */
const precioSoles = '45.50';

/** Stock entero ≥ 1 exigido por Story 1. */
const stock = '1';

/** Categoría del seed usada para clasificar la publicación de la precondición (Story 1). */
const categoriaSeed = 'Electrónica';

/** Subcategoría del seed de la categoría elegida (existe en R__seed_demo.sql). */
const subcategoriaSeed = 'Auriculares';

/** Credenciales documentadas del vendedor de demostración (R__seed_demo.sql, PHA06TSK08). */
const emailVendedorDemo = 'vendedor@easymarket.dev';
const contrasenaVendedorDemo = 'VendedorPass123!';

/** Etiquetas de los 7 destinos del rol USUARIO (contrato PHA06TSK09, Shell.tsx). */
const etiquetasDestinosUsuario = [
  'Mercado',
  'Publicar',
  'Mis publicaciones',
  'Compras',
  'Ventas',
  'Notificaciones',
  'Saldo',
] as const;

/** Etiquetas de los 5 destinos exclusivos del rol ADMIN (contrato PHA06TSK09, Shell.tsx). */
const etiquetasDestinosAdmin = [
  'Administración',
  'Moderación',
  'Categorías',
  'Disputas',
  'Cuentas bloqueadas',
] as const;

/** Única página compartida por todo el recorrido (conserva cookie y navegación). */
let pagina: Page;

/**
 * Lee el {@code .env} raíz (formato {@code NOMBRE=valor} por línea, ignorando
 * comentarios y líneas vacías) y devuelve sus variables como mapa.
 *
 * <p>Espejo local de {@code leerEnvArchivo} de {@code entorno.ts}, que es
 * privada del harness. Se usa para obtener {@code ADMIN_EMAIL} y
 * {@code ADMIN_PASSWORD} (credenciales del gate 9). Los valores nunca se
 * imprimen; solo se inyectan en el formulario de login.</p>
 *
 * @param ruta ruta absoluta del archivo {@code .env}
 * @returns mapa {@code NOMBRE → valor}; si el archivo no existe, mapa vacío
 */
function leerEnvRaiz(ruta: string): Record<string, string> {
  const mapa: Record<string, string> = {};
  let contenido: string;
  try {
    contenido = readFileSync(ruta, 'utf8');
  } catch {
    return mapa;
  }
  for (const linea of contenido.split(/\r?\n/)) {
    const recortada = linea.trim();
    if (recortada.length === 0 || recortada.startsWith('#')) {
      continue;
    }
    const indice = recortada.indexOf('=');
    if (indice < 1) {
      continue;
    }
    mapa[recortada.slice(0, indice).trim()] = recortada.slice(indice + 1).trim();
  }
  return mapa;
}

/**
 * Pulsa un enlace y espera la URL destino, reintentando si el clic se pierde.
 *
 * <p>Next.js en modo dev compila rutas on-demand: el primer acceso a una ruta
 * aún no compilada dispara una recompilación del dev server y el clic sobre un
 * {@code Link} puede descartarse si la compilación coincide con el envío del
 * evento (fenómeno real observado en este entorno; ver Artifact PHA07TSK06-L01).
 * El reintento solo se dispara si la URL NO cambió en el intento anterior, de
 * modo que una navegación lenta pero exitosa no se duplica, y un fallo real de
 * la aplicación (enlace roto, ruta inexistente) sigue fallando tras agotar los
 * intentos: el helper absorbe la race del dev server, no enmascara errores.</p>
 *
 * @param enlace locator del enlace a pulsar (se re-resuelve en cada intento)
 * @param patron expresión regular de la URL destino esperada
 * @returns promesa resuelta cuando la URL coincide con {@code patron}
 * @throws error del último intento si la navegación no ocurrió en ningún intento
 */
async function navegarConReintento(enlace: Locator, patron: RegExp): Promise<void> {
  const TIEMPO_ESPERA_MS = 15_000;
  const INTENTOS = 2;
  let ultimoError: unknown;
  for (let intento = 1; intento <= INTENTOS; intento += 1) {
    try {
      await Promise.all([pagina.waitForURL(patron, { timeout: TIEMPO_ESPERA_MS }), enlace.click()]);
      return;
    } catch (error) {
      ultimoError = error;
    }
  }
  if (ultimoError instanceof Error) {
    throw ultimoError;
  }
  throw new Error(`No se pudo navegar a ${patron} tras ${INTENTOS} intentos.`);
}

/**
 * Localiza la tarjeta del dashboard administrativo por la etiqueta de su métrica.
 *
 * <p>Cada tarjeta es un {@code article} con un {@code h2} (etiqueta de la
 * métrica) y un {@code p} (valor real del endpoint {@code /admin/tablero};
 * ver {@code DashboardAdmin} en AdminUI.tsx). El filtro por {@code h2} con
 * {@code exact: true} aísla la tarjeta correcta sin depender del orden.</p>
 *
 * @param etiqueta texto literal de la métrica ({@code h2} de la tarjeta)
 * @returns locator del {@code article} que contiene la métrica solicitada
 */
function tarjetaDashboard(etiqueta: string): Locator {
  return pagina.locator('article').filter({
    has: pagina.getByRole('heading', { level: 2, name: etiqueta, exact: true }),
  });
}

/**
 * Abre el menú lateral de navegación (hamburguesa) y espera a que quede expandido.
 *
 * <p>Desde la reorganización del shell (sidebar agrupado colapsado por defecto),
 * los enlaces de navegación y el botón "Cerrar sesión" viven dentro del sidebar
 * con {@code pointer-events-none} mientras está cerrado: antes de cualquier clic
 * sobre un enlace del nav o sobre "Cerrar sesión" hay que expandir el menú. El
 * botón hamburguesa es el único elemento con {@code aria-controls="sidebar-navegacion"}
 * y expone su estado en {@code aria-expanded}.</p>
 *
 * @returns promesa resuelta cuando el sidebar quedó expandido
 */
async function abrirMenu(): Promise<void> {
  const botonMenu = pagina.locator('button[aria-controls="sidebar-navegacion"]');
  if ((await botonMenu.getAttribute('aria-expanded')) !== 'true') {
    await botonMenu.click();
  }
  await expect(botonMenu).toHaveAttribute('aria-expanded', 'true');
}

test.describe.serial('Recorrido completo de ADMIN (PHA07TSK07)', () => {
  /**
   * Crea el contexto y la página compartidos del recorrido serial.
   *
   * <p>Se usa el fixture {@code browser} (worker-scoped) porque cada {@code test}
   * del grupo serial recibe una página fresca por defecto; compartir una única
   * instancia es lo que permite conservar la cookie httpOnly {@code jwt} del
   * login y el estado de navegación real entre los 8 pasos.</p>
   *
   * @param fixtures fixtures worker-scoped de Playwright ({@code browser})
   * @returns promesa resuelta cuando la página compartida está lista
   */
  test.beforeAll(async ({ browser }) => {
    const contexto = await browser.newContext();
    pagina = await contexto.newPage();
  });

  /**
   * Cierra el contexto compartido al terminar el grupo serial.
   *
   * @returns promesa resuelta al cerrar el contexto
   */
  test.afterAll(async () => {
    await pagina?.context().close();
  });

  test('paso 1 — precondición: login del vendedor demo, publicación pendiente única y logout', async () => {
    // Endpoints reales ejercitados indirectamente: POST /auth/login (PHA01TSK07),
    // POST /publicaciones (creación en PENDIENTE_REVISION; PHA02TSK09/PHA06TSK02)
    // y POST /auth/logout (PHA07TSK03). Crea el material real que el ADMIN
    // moderará en el paso 4, con datos únicos por corrida (Date.now()).
    await pagina.goto('/auth');
    await pagina.getByRole('tab', { name: 'Iniciar sesión' }).click();
    await pagina.getByLabel('Email').fill(emailVendedorDemo);
    await pagina.getByLabel('Contraseña').fill(contrasenaVendedorDemo);
    await pagina.getByRole('button', { name: 'Ingresar' }).click();
    await expect(pagina).toHaveURL(/\/publicaciones$/);
    await abrirMenu();
    await navegarConReintento(pagina.getByRole('link', { name: 'Publicar', exact: true }), /\/publicar$/);
    const categoriaSelect = pagina.getByLabel('Categoría', { exact: true });
    const subcategoriaSelect = pagina.getByLabel('Subcategoría', { exact: true });
    // Espera la carga REAL del catálogo (el select se habilita tras GET /categorias).
    await expect(categoriaSelect).toBeEnabled();
    await categoriaSelect.selectOption({ label: categoriaSeed });
    await expect(subcategoriaSelect).toBeEnabled();
    await subcategoriaSelect.selectOption({ label: subcategoriaSeed });
    await pagina.getByLabel('Precio (S/)').fill(precioSoles);
    await pagina.getByLabel('Stock').fill(stock);
    await pagina.getByLabel('Descripción').fill(descripcionPublicacion);
    await pagina.getByRole('button', { name: 'Publicar' }).click();
    // Redirección canónica post-creación a la fuente de las publicaciones propias.
    await expect(pagina).toHaveURL(/\/mis-publicaciones$/);
    // Cierra la sesión del vendedor para dejar el navegador limpio antes del login ADMIN.
    await abrirMenu();
    await pagina.getByRole('button', { name: 'Cerrar sesión' }).click();
    await expect(pagina).toHaveURL(/\/auth$/);
  });

  test('paso 2 — login ADMIN con credenciales del .env raíz y shell con los 12 destinos del rol', async () => {
    // Validación E2E del gate 9 del harness (PHA07TSK05): si ADMIN_EMAIL o
    // ADMIN_PASSWORD del .env raíz fueran inválidas, POST /auth/login falla,
    // no hay cookie jwt y el shell jamás muestra los 12 destinos del rol.
    // El valor de la contraseña se inyecta sin imprimirse jamás.
    const envRaiz = leerEnvRaiz(ENV_RAIZ);
    const adminEmail = envRaiz['ADMIN_EMAIL'];
    const adminPassword = envRaiz['ADMIN_PASSWORD'];
    expect(adminEmail, 'ADMIN_EMAIL debe estar definido en el .env raíz (gate 9)').toBeTruthy();
    expect(adminPassword, 'ADMIN_PASSWORD debe estar definido en el .env raíz (gate 9)').toBeTruthy();
    await pagina.getByRole('tab', { name: 'Iniciar sesión' }).click();
    await pagina.getByLabel('Email').fill(adminEmail);
    await pagina.getByLabel('Contraseña').fill(adminPassword);
    await pagina.getByRole('button', { name: 'Ingresar' }).click();
    await expect(pagina).toHaveURL(/\/publicaciones$/);
    // 12 destinos del shell: 7 del rol USUARIO + 5 exclusivos de ADMIN (Shell.tsx).
    await abrirMenu();
    const nav = pagina.getByRole('navigation', { name: 'Navegación principal' });
    for (const etiqueta of etiquetasDestinosUsuario) {
      await expect(nav.getByRole('link', { name: etiqueta, exact: true })).toBeVisible();
    }
    for (const etiqueta of etiquetasDestinosAdmin) {
      await expect(nav.getByRole('link', { name: etiqueta, exact: true })).toBeVisible();
    }
    await expect(pagina.getByRole('button', { name: 'Cerrar sesión' })).toBeVisible();
  });

  test('paso 3 — dashboard (Story 13): las 6 métricas reales de GET /admin/tablero', async () => {
    // Endpoint real ejercitado indirectamente: GET /admin/tablero (PHA07TSK01).
    // "Volumen en escrow" es DINÁMICO desde PHA07TSK08 (decisión de Lino
    // 2026-08-16, reapertura de TSK07 en L02): la suite de compra con escrow
    // crea transacciones `reservada` REALES de S/ 99.99 por corrida (pago
    // sandbox con webhook real), así que el valor real ya no es siempre
    // "S/ 0.00" — se asevera con el patrón ^S\/ \d+\.\d{2}$ (acepta BD
    // limpia y estado con reservadas; exige soles bien formados en centavos,
    // principio 3 de constitution.md). Las otras 5 métricas siguen estáticas:
    // el seed NO crea bloqueos (PHA06TSK08), TSK08 no crea disputas, no
    // libera fondos ni finaliza transacciones, y este recorrido no modifica
    // esas fuentes: disputas, cuentas bloqueadas, fondos y transacciones se
    // aseveran en 0/"S/ 0.00". Dinámica además: "Publicaciones pendientes",
    // que siempre incluye la publicación creada en el paso 1 (además de las
    // 2 del seed).
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Administración', exact: true }),
      /\/admin$/
    );
    await expect(pagina.getByRole('heading', { level: 1, name: 'Administración operativa' })).toBeVisible();
    await expect(tarjetaDashboard('Disputas abiertas').locator('p')).toHaveText('0');
    await expect(tarjetaDashboard('Cuentas bloqueadas').locator('p')).toHaveText('0');
    await expect(tarjetaDashboard('Volumen en escrow').locator('p')).toHaveText(/^S\/ \d+\.\d{2}$/);
    await expect(tarjetaDashboard('Fondos liberados').locator('p')).toHaveText('S/ 0.00');
    await expect(tarjetaDashboard('Transacciones finalizadas').locator('p')).toHaveText('0');
    const valorPendientes = await tarjetaDashboard('Publicaciones pendientes').locator('p').textContent();
    expect(valorPendientes, 'la tarjeta debe mostrar un conteo numérico real').toMatch(/^\d+$/);
    expect(
      Number(valorPendientes),
      'debe existir al menos la publicación creada en el paso 1 de este recorrido'
    ).toBeGreaterThanOrEqual(1);
  });

  test('paso 4 — moderación: aprobar la publicación creada en la precondición', async () => {
    // Endpoint real ejercitado indirectamente: POST /publicaciones/{id}/moderar
    // (PHA06TSK13). Se modera EXCLUSIVAMENTE la publicación única de este
    // recorrido (por su descripción Date.now()), nunca las del seed: cada
    // corrida es re-ejecutable y no altera el material de las siguientes.
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Moderación', exact: true }),
      /\/admin\/moderacion$/
    );
    const tarjeta = pagina.locator('div.space-y-4').filter({
      has: pagina.getByText(descripcionPublicacion),
    });
    await expect(tarjeta).toHaveCount(1);
    await tarjeta.getByRole('button', { name: 'Aprobar' }).click();
    await expect(pagina.getByText('Publicación moderada exitosamente')).toBeVisible();
    // La publicación aprobada sale de la lista de pendientes (estado real APROBADA).
    await expect(pagina.getByText(descripcionPublicacion)).toHaveCount(0);
  });

  test('paso 5 — catálogo (Story 13): CRUD real de una categoría única', async () => {
    // Endpoints reales ejercitados indirectamente: POST /categorias, PUT
    // /categorias/{id} y DELETE /categorias/{id} (PHA06TSK07). La categoría
    // se crea SIN subcategorías para que la eliminación no tope con la
    // integridad referencial de las publicaciones seed. Nombre único por
    // corrida para no colisionar con corridas anteriores.
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Categorías', exact: true }),
      /\/admin\/categorias$/
    );
    const nombreCategoria = `Categoría E2E TSK07 ${Date.now()}`;
    const nombreRenombrado = `${nombreCategoria} (editada)`;
    // Crear: formulario superior con label real y botón "Crear Categoría".
    await pagina.getByLabel('Nombre de la Nueva Categoría').fill(nombreCategoria);
    await pagina.getByRole('button', { name: 'Crear Categoría', exact: true }).click();
    await expect(pagina.getByText('Categoría creada exitosamente')).toBeVisible();
    // El h2 de la tarjeta es el nombre único; el label "Nueva subcategoría de
    // {nombre}" también contiene el nombre, por eso la presencia/ausencia se
    // asevera por rol heading (único por tarjeta) y no por texto genérico.
    await expect(pagina.getByRole('heading', { name: nombreCategoria, exact: true })).toBeVisible();
    // Editar: aria-label "Editar categoría {nombre}" abre el editor con label
    // "Nuevo nombre de la categoría {nombreOriginal}" y botón "Guardar categoría {nombreOriginal}".
    await pagina.getByRole('button', { name: `Editar categoría ${nombreCategoria}` }).click();
    await pagina.getByLabel(`Nuevo nombre de la categoría ${nombreCategoria}`).fill(nombreRenombrado);
    await pagina.getByRole('button', { name: `Guardar categoría ${nombreCategoria}` }).click();
    await expect(pagina.getByText('Categoría editada exitosamente')).toBeVisible();
    await expect(pagina.getByRole('heading', { name: nombreRenombrado, exact: true })).toBeVisible();
    // Eliminar: aria-label "Eliminar categoría {nombre}" elimina sin confirmación previa.
    await pagina.getByRole('button', { name: `Eliminar categoría ${nombreRenombrado}` }).click();
    await expect(pagina.getByText('Categoría eliminada exitosamente')).toBeVisible();
    await expect(pagina.getByRole('heading', { name: nombreRenombrado, exact: true })).toHaveCount(0);
  });

  test('paso 6 — disputas con estado vacío legítimo (decisión de Lino 2026-08-16)', async () => {
    // Endpoint real ejercitado indirectamente: GET /admin/disputas (PHA07TSK02).
    // El seed NO crea transacciones por diseño (PHA06TSK08) y el pago/escrow
    // real es de PHA07TSK08: el estado esperado es vacío REAL tras un 200 con
    // lista vacía (el componente solo muestra el texto cuando cargó sin error).
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Disputas', exact: true }),
      /\/admin\/disputas$/
    );
    await expect(pagina.getByText('No hay disputas pendientes de resolución.')).toBeVisible();
  });

  test('paso 7 — cuentas bloqueadas con estado vacío legítimo (decisión de Lino 2026-08-16)', async () => {
    // Endpoint real ejercitado indirectamente: GET /admin/usuarios/bloqueados
    // (PHA06TSK12). El seed NO crea bloqueos por diseño (PHA06TSK08): estado
    // vacío REAL tras un 200 con lista vacía.
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Cuentas bloqueadas', exact: true }),
      /\/admin\/usuarios-bloqueados$/
    );
    await expect(pagina.getByText('No hay cuentas con bloqueo permanente.')).toBeVisible();
  });

  test('paso 8 — aislamiento de roles: USUARIO no accede a rutas ni acciones de ADMIN', async () => {
    // Endpoints reales ejercitados indirectamente: POST /auth/logout y
    // POST /auth/login (PHA07TSK03/PHA01TSK07) para cambiar de sesión, y
    // GET /usuarios/me para que el shell y SoloAdmin (AdminUI.tsx) resuelvan
    // el rol USUARIO. La barrera es visual y de navegación: el alert de
    // SoloAdmin solo se renderiza con rol distinto de ADMIN.
    await abrirMenu();
    await pagina.getByRole('button', { name: 'Cerrar sesión' }).click();
    await expect(pagina).toHaveURL(/\/auth$/);
    await pagina.getByRole('tab', { name: 'Iniciar sesión' }).click();
    await pagina.getByLabel('Email').fill(emailVendedorDemo);
    await pagina.getByLabel('Contraseña').fill(contrasenaVendedorDemo);
    await pagina.getByRole('button', { name: 'Ingresar' }).click();
    await expect(pagina).toHaveURL(/\/publicaciones$/);
    // Acceso directo a /admin: barrera SoloAdmin en lugar del dashboard.
    // Se asevera por el texto exacto de la barrera (no por role="alert"
    // genérico): Next.js inyecta el route announcer con role="alert" y
    // aria-live, que duplicaría el locator según el momento de la navegación.
    await pagina.goto('/admin');
    const barreraSoloAdmin = pagina.getByText('No tienes permisos para acceder a esta sección.', { exact: true });
    await expect(barreraSoloAdmin).toHaveCount(1);
    await expect(barreraSoloAdmin).toBeVisible();
    // El shell de USUARIO no ofrece ninguno de los 5 destinos exclusivos de ADMIN.
    await abrirMenu();
    const nav = pagina.getByRole('navigation', { name: 'Navegación principal' });
    for (const etiqueta of etiquetasDestinosAdmin) {
      await expect(nav.getByRole('link', { name: etiqueta, exact: true })).toHaveCount(0);
    }
    // Ninguna acción administrativa queda operativa en el DOM.
    await expect(pagina.getByRole('button', { name: 'Crear Categoría', exact: true })).toHaveCount(0);
    await expect(pagina.getByRole('button', { name: 'Aprobar', exact: true })).toHaveCount(0);
    // El submódulo /admin/categorias también muestra la barrera, no el CRUD.
    await pagina.goto('/admin/categorias');
    await expect(barreraSoloAdmin).toHaveCount(1);
    await expect(barreraSoloAdmin).toBeVisible();
  });
});