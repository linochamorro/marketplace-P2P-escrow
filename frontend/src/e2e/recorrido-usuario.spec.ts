/**
 * Spec E2E — Recorrido completo de USUARIO sin pago (PHA07TSK06).
 *
 * <p>Verifica el flujo navegable completo del rol USUARIO contra los servicios
 * REALES del entorno local (PostgreSQL + backend dev + frontend + Stripe CLI
 * validados por el harness de PHA07TSK05), sin ningún mock de producción: no
 * se usa {@code page.route}, no se intercepta red y no se sustituye el
 * transporte de datos. Cada aserción sobre contenido renderizado ES la
 * verificación indirecta de la respuesta real del endpoint que la produce.</p>
 *
 * <p>Traza a las Stories 0 (registro), 0b (login), 1 (publicar), 5 (compra —
 * solo detalle con botón, SIN pagar: el pago/escrow real es de PHA07TSK08),
 * 7b (notificaciones), 10 (stock/estado en mis publicaciones), 11 (listado y
 * filtros) y 12 (saldo) de spec.md, y a plan.md Fase 7 (Entorno E2E y Cuentas
 * E2E). El recorrido es: landing → registro → login → marketplace →
 * filtros/detalle → publicar → mis publicaciones → notificaciones/saldo →
 * compras/ventas.</p>
 *
 * <p>Orden estricto y serial: el flujo es secuencial y con estado (registro →
 * login → publicar → ...). Se usa {@code test.describe.serial} con una única
 * {@code Page} compartida creada en {@code beforeAll} para conservar la cookie
 * de sesión {@code jwt} (httpOnly) y la navegación real entre pasos; cada
 * paso es un {@code test} del grupo serial. Otros spec files (preparación,
 * home) corren en workers separados (config {@code fullyParallel: true});
 * este archivo es autocontenido y no depende del estado de otros specs.</p>
 *
 * <p>Email único por corrida: {@code usuario-e2e-<timestamp>@example.com}
 * evita colisiones de unicidad ({@code usuarios.email} único) en re-corridas
 * del spec y garantiza que la lista {@code GET /publicaciones/mias} del
 * usuario fresco arranque vacía salvo la publicación creada en este mismo
 * recorrido.</p>
 *
 * <p>Compras/ventas con estado vacío legítimo (decisión de Lino 2026-08-16,
 * vinculante): se navega a {@code /compras} y {@code /ventas} y se asevera
 * que la pantalla carga con datos reales del endpoint correspondiente. El
 * seed {@code R__seed_demo.sql} NO crea transacciones por diseño
 * (PHA06TSK08) y el pago/escrow real es de PHA07TSK08, por lo que el estado
 * esperado es vacío real: {@code "Aún no tienes compras."} y
 * {@code "Aún no tienes ventas."} (TransaccionesUI.tsx, línea 237). Aseverar
 * ese texto vacío (que el componente solo renderiza tras un 200 con lista
 * vacía y sin error) prueba un GET exitoso real; no es un mock ni alcance
 * nuevo.</p>
 *
 * <p>Datos de seed aseverados (R__seed_demo.sql, PHA06TSK08): el marketplace
 * {@code GET /publicaciones} expone solo las 2 publicaciones {@code aprobada}
 * del vendedor demo — "Auriculares inalámbricos Bluetooth con cancelación de
 * ruido" y "Smartwatch deportivo con GPS y monitor de ritmo cardíaco" — y el
 * catálogo {@code GET /categorias} incluye la categoría real "Electrónica"
 * con sus subcategorías. El filtro por categoría usa el contrato real de
 * query params de Story 11 ({@code GET /publicaciones?categoriaId=N}).</p>
 *
 * <p>Robustez ante compilación on-demand de Next.js en modo dev: el primer
 * acceso a una ruta aún no compilada dispara una recompilación del dev server
 * y el clic sobre un {@code Link} puede descartarse si la compilación coincide
 * con el envío del evento (fenómeno real observado en este entorno; ver
 * {@code navegarConReintento}). Las navegaciones por enlace del recorrido
 * usan ese helper; las redirecciones programáticas (login, publicación)
 * ya esperan la compilación internamente.</p>
 */

import { test, expect, type Locator, type Page } from '@playwright/test';

test.describe.serial('Recorrido completo de USUARIO sin pago (PHA07TSK06)', () => {
  /** Email único por corrida: evita colisiones de unicidad en re-corridas. */
  const emailUnico = `usuario-e2e-${Date.now()}@example.com`;
  /** Contraseña que cumple la política mínima de Story 0 (≥ 8 caracteres). */
  const contrasena = 'ClaveE2E123!';
  /** Descripción única de la publicación creada en este recorrido. */
  const descripcionPublicacion = `Publicación E2E TSK06 ${Date.now()}`;
  /** Precio en soles ingresado en la UI (se convierte a centavos en la frontera HTTP). */
  const precioSoles = '88.50';
  /** Stock entero ≥ 1 exigido por Story 1. */
  const stock = '2';
  /** Descripción literal del Smartwatch aprobado del seed (Story 11). */
  const smartwatchSeed = 'Smartwatch deportivo con GPS y monitor de ritmo cardíaco';

  /** Única página compartida por todo el recorrido (conserva cookie y navegación). */
  let pagina: Page;

  /**
   * Crea el contexto y la página compartidos del recorrido serial.
   *
   * <p>Se usa el fixture {@code browser} (worker-scoped) porque cada {@code test}
   * del grupo serial recibe una página fresca por defecto; compartir una única
   * instancia es lo que permite conservar la cookie httpOnly {@code jwt} del
   * login y el estado de navegación real entre los 10 pasos.</p>
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

  /**
   * Pulsa un enlace y espera la URL destino, reintentando si el clic se pierde.
   *
   * <p>Next.js en modo dev compila rutas on-demand: el primer acceso a una ruta
   * aún no compilada (p. ej. {@code /publicaciones/[id]}, {@code /publicar},
   * {@code /saldo}) dispara una recompilación del dev server y el clic sobre un
   * {@code Link} puede descartarse si la compilación coincide con el envío del
   * evento (observado en este entorno: la navegación no ocurría y el log del
   * browser mostraba "[Fast Refresh] rebuilding"; ver Artifact PHA07TSK06-L01).
   * El reintento solo se dispara si la URL NO cambió en el intento anterior,
   * de modo que una navegación lenta pero exitosa no se duplica, y un fallo
   * real de la aplicación (enlace roto, ruta inexistente) sigue fallando tras
   * agotar los intentos: el helper absorbe la race del dev server, no enmascara
   * errores de la app.</p>
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

  test('paso 1 — landing sin sesión: heading EasyMarket y CTA hacia /auth', async () => {
    // Story: "Inicio" (PHA06TSK09, home.tsx) — sin cookie jwt la home muestra la landing.
    await pagina.goto('/');
    await expect(pagina.getByRole('heading', { level: 1 })).toContainText(/EasyMarket/);
    const cta = pagina.getByRole('link', { name: /iniciar sesión|crear cuenta/i });
    await expect(cta).toBeVisible();
    await expect(cta).toHaveAttribute('href', '/auth');
  });

  test('paso 2 — registro (Story 0): éxito local y SIN auto-login', async () => {
    // Endpoint real ejercitado indirectamente: POST /auth/registro (AuthController, PHA01TSK06).
    await pagina.goto('/auth');
    await expect(pagina.getByRole('tab', { name: 'Crear cuenta' })).toBeVisible();
    await pagina.getByLabel('Email').fill(emailUnico);
    await pagina.getByLabel('Contraseña').fill(contrasena);
    await pagina.getByRole('button', { name: 'Registrarse' }).click();
    await expect(pagina.getByText('Registro exitoso. Ya puedes iniciar sesión.')).toBeVisible();
    // Sin auto-login (auth/page.tsx): se permanece en /auth y no existe cookie jwt.
    await expect(pagina).toHaveURL(/\/auth$/);
    const cookies = await pagina.context().cookies();
    expect(cookies.some((c) => c.name === 'jwt'), 'el registro NO debe emitir cookie de sesión').toBe(false);
  });

  test('paso 3 — login (Story 0b): cookie jwt httpOnly y redirección a /publicaciones', async () => {
    // Endpoint real ejercitado indirectamente: POST /auth/login (Set-Cookie jwt httpOnly; PHA01TSK07).
    await pagina.getByRole('tab', { name: 'Iniciar sesión' }).click();
    await pagina.getByLabel('Email').fill(emailUnico);
    await pagina.getByLabel('Contraseña').fill(contrasena);
    await pagina.getByRole('button', { name: 'Ingresar' }).click();
    await expect(pagina).toHaveURL(/\/publicaciones$/);
    const cookies = await pagina.context().cookies();
    const jwt = cookies.find((c) => c.name === 'jwt');
    expect(jwt, 'cookie jwt presente tras login exitoso').toBeTruthy();
    expect(jwt?.httpOnly, 'la cookie jwt debe ser httpOnly (nunca legible por JS)').toBe(true);
  });

  test('paso 4 — marketplace (Story 11): seed real y filtro por categoría Electrónica', async () => {
    // Endpoints reales ejercitados indirectamente: GET /publicaciones y
    // GET /publicaciones?categoriaId=N (PHA05TSK03) + GET /categorias (PHA02TSK15).
    await expect(pagina.getByRole('heading', { level: 1, name: 'Publicaciones' })).toBeVisible();
    const auricularesSeed = pagina.getByText('Auriculares inalámbricos Bluetooth con cancelación de ruido');
    const smartwatch = pagina.getByText(smartwatchSeed);
    await expect(auricularesSeed).toBeVisible();
    await expect(smartwatch).toBeVisible();
    // Filtro por categoría REAL cargada desde GET /categorias (opción "Electrónica").
    await pagina.getByLabel('Categoría', { exact: true }).selectOption({ label: 'Electrónica' });
    await pagina.getByRole('button', { name: 'Aplicar filtros' }).click();
    // Contrato real con query params: ambas publicaciones aprobadas del seed son de
    // Electrónica, por lo que el listado filtrado las conserva (respuesta real, sin mocks).
    await expect(auricularesSeed).toBeVisible();
    await expect(smartwatch).toBeVisible();
  });

  test('paso 5 — detalle (Stories 5/11): descripción, precio en soles y stock; botón de compra presente sin pagar', async () => {
    // Endpoint real ejercitado indirectamente: GET /publicaciones/{id} (PHA06TSK04).
    await navegarConReintento(
      pagina.getByRole('link', { name: `Ver detalle de ${smartwatchSeed}` }),
      /\/publicaciones\/\d+$/
    );
    await expect(pagina.getByRole('heading', { level: 1 })).toContainText(smartwatchSeed);
    // 45900 centavos → "S/ 459.00" (conversión visual /100; dinero entero en el contrato).
    await expect(pagina.getByText('S/ 459.00')).toBeVisible();
    await expect(pagina.getByText('Stock disponible: 3')).toBeVisible();
    // El botón de compra está presente pero NO se interactúa con pago (eso es PHA07TSK08).
    await expect(pagina.getByRole('button', { name: 'Comprar' })).toBeVisible();
    // La ruta de detalle NO lleva el shell de navegación (esRutaConShell excluye
    // /publicaciones/[id] en PHA06TSK09): se regresa al marketplace con el botón
    // atrás del navegador (acción real de usuario) para continuar el recorrido.
    await pagina.goBack();
    await expect(pagina).toHaveURL(/\/publicaciones$/);
  });

  test('paso 6 — publicar (Story 1): formulario con pareja categoría/subcategoría real y POST /publicaciones', async () => {
    // Endpoints reales ejercitados indirectamente: GET /categorias (carga del formulario)
    // y POST /publicaciones (creación en estado PENDIENTE_REVISION; PHA02TSK09/PHA06TSK02).
    await abrirMenu();
    await navegarConReintento(pagina.getByRole('link', { name: 'Publicar', exact: true }), /\/publicar$/);
    const categoriaSelect = pagina.getByLabel('Categoría', { exact: true });
    const subcategoriaSelect = pagina.getByLabel('Subcategoría', { exact: true });
    // Espera la carga REAL del catálogo (el select se habilita tras GET /categorias).
    await expect(categoriaSelect).toBeEnabled();
    await categoriaSelect.selectOption({ label: 'Electrónica' });
    await expect(subcategoriaSelect).toBeEnabled();
    await pagina.getByLabel('Precio (S/)').fill(precioSoles);
    await pagina.getByLabel('Stock').fill(stock);
    await pagina.getByLabel('Descripción').fill(descripcionPublicacion);
    await pagina.getByRole('button', { name: 'Publicar' }).click();
    // Redirección canónica post-creación a la fuente de las publicaciones propias.
    await expect(pagina).toHaveURL(/\/mis-publicaciones$/);
  });

  test('paso 7 — mis publicaciones (Story 10): la publicación creada aparece en PENDIENTE_REVISION', async () => {
    // Endpoint real ejercitado indirectamente: GET /publicaciones/mias (PHA02TSK17).
    await expect(pagina.getByRole('heading', { level: 1, name: 'Mis publicaciones' })).toBeVisible();
    await expect(pagina.getByText(descripcionPublicacion)).toBeVisible();
    // El backend serializa el estado como nombre del enum: PENDIENTE_REVISION (PublicacionResponseDto).
    await expect(pagina.getByText('PENDIENTE_REVISION')).toBeVisible();
  });

  test('paso 8 — notificaciones (Story 7b): pantalla cargada con datos reales de GET /notificaciones', async () => {
    // Endpoint real ejercitado indirectamente: GET /notificaciones (PHA04TSK16).
    // Para un usuario nuevo sin transacciones el estado es vacío legítimo tras un 200
    // real con lista vacía: el componente solo muestra este texto cuando cargó sin error.
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Notificaciones', exact: true }),
      /\/notificaciones$/
    );
    await expect(pagina.getByRole('heading', { level: 1, name: 'Centro de Notificaciones' })).toBeVisible();
    await expect(pagina.getByText('No tienes notificaciones.')).toBeVisible();
  });

  test('paso 9 — saldo (Story 12): panel con datos reales de GET /usuarios/me/saldo', async () => {
    // Endpoint real ejercitado indirectamente: GET /usuarios/me/saldo (PHA04TSK17).
    // Usuario nuevo: saldoDisponible 0 → "S/ 0.00" y movimientos vacíos (PanelSaldo).
    await abrirMenu();
    await navegarConReintento(pagina.getByRole('link', { name: 'Saldo', exact: true }), /\/saldo$/);
    await expect(pagina.getByRole('heading', { level: 1, name: 'Panel de Saldo' })).toBeVisible();
    await expect(pagina.getByText('S/ 0.00')).toBeVisible();
    await expect(pagina.getByText('Todavía no tienes movimientos de saldo.')).toBeVisible();
  });

  test('paso 10 — compras/ventas con estado vacío legítimo (decisión de Lino 2026-08-16)', async () => {
    // Endpoints reales ejercitados indirectamente: GET /transacciones/compras y
    // GET /transacciones/ventas (PHA06TSK06). El seed NO crea transacciones por diseño
    // (PHA06TSK08) y el pago/escrow real es de PHA07TSK08; aseverar el estado vacío real
    // ("Aún no tienes compras." / "Aún no tienes ventas.", TransaccionesUI.tsx:237) prueba
    // un GET exitoso con lista vacía — no es un mock ni alcance nuevo.
    await abrirMenu();
    await navegarConReintento(pagina.getByRole('link', { name: 'Compras', exact: true }), /\/compras$/);
    await expect(pagina.getByText('Aún no tienes compras.')).toBeVisible();
    await abrirMenu();
    await navegarConReintento(pagina.getByRole('link', { name: 'Ventas', exact: true }), /\/ventas$/);
    await expect(pagina.getByText('Aún no tienes ventas.')).toBeVisible();
  });
});