/**
 * Spec E2E — Compra con escrow y Stripe sandbox real (PHA07TSK08).
 *
 * <p>Verifica el flujo navegable completo de compra contra los servicios REALES
 * del entorno local (PostgreSQL + backend dev + frontend + Stripe CLI
 * validados por el harness de PHA07TSK05), sin ningún mock de producción: no
 * se usa {@code page.route}, no se intercepta red y no se sustituye el
 * transporte de datos. Cada aserción sobre contenido renderizado ES la
 * verificación indirecta de la respuesta real del endpoint que la produce.</p>
 *
 * <p>Traza a las Stories 5 (pago con tarjeta), 6a-6d (flujo de estados del
 * escrow), 7 (cancelación) y 12 (saldo no afectado en reserva) de spec.md, y a
 * plan.md Fase 7 (Entorno E2E real con Stripe sandbox — filas 534 y 535 — y
 * Harness E2E — fila 539: el pago se prueba con tarjeta de prueba real y
 * webhook real reenviado por la CLI, nunca con un gateway falso).</p>
 *
 * <p>Recorrido: precondición con el vendedor demo (crea DOS publicaciones
 * únicas reales: una que se comprará con éxito y otra con la que se fallará el
 * pago — decisión documentada en el Artifact: cada corrida crea material
 * propio y NO compra publicaciones del seed, porque PHA07TSK06 asevera el
 * stock del Smartwatch del seed) → login ADMIN con credenciales del {@code .env}
 * raíz (gate 9) que aprueba ambas → compra real del comprador demo con la
 * tarjeta de prueba {@code 4242...} en el PaymentElement embebido de Stripe
 * (POST /compras → PaymentIntent → confirmPayment → webhook
 * {@code payment_intent.succeeded} reenviado por la CLI → transacción
 * {@code reservada} + decremento atómico de stock) → verificación de la
 * transacción en /compras → vendedor ve la venta en /ventas con las acciones
 * de escrow de reservada → compra fallida con la tarjeta declinada
 * {@code 4000...0002} que NO crea transacción ni consume stock → saldo del
 * vendedor intacto (Story 12).</p>
 *
 * <p>Cadena de evidencia indirecta (honestidad del E2E): el estado
 * {@code reservada} visible en /compras tras el pago solo puede existir si el
 * webhook {@code payment_intent.succeeded} fue procesado por el backend
 * (ProcesadorEventosWebhookService → transacción + stock en la MISMA
 * transacción atómica de BD, plan.md flujo de compra PHA03); no hay camino
 * alternativo en la aplicación que cree una transacción desde la UI.</p>
 *
 * <p>Orden estricto y serial: el flujo es secuencial y con estado (login →
 * creación → moderación → compra → webhook → ventas → fallo). Se usa
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
 * <p>Selectores del PaymentElement de Stripe: los campos del formulario de
 * pago viven en un iframe cross-origin de {@code js.stripe.com}. La iteración
 * empírica (corrida focal L01, documentada en el Artifact) mostró que el
 * PaymentElement monta DOS iframes con título {@code "Secure payment input
 * frame"} y que los inputs no se exponen con {@code name} útil; la estructura
 * definitiva (confirmada por snapshot de accesibilidad) es un único iframe
 * dentro del div {@code aria-label="Formulario de pago Stripe"} con textboxes
 * accesibles "Card number", "Expiration date" y "Security code". El helper
 * {@code llenarFormularioPagoStripe} acota el iframe a ese contenedor y llena
 * por rol/textbox con nombre accesible.</p>
 *
 * <p>Espera del webhook: {@code payment_intent.succeeded} viaja de Stripe a la
 * CLI y de la CLI al backend. La espera es un polling por recarga de
 * {@code /compras} acotado (~60 s): honesto — si el webhook nunca llega, el
 * paso falla por timeout real en lugar de asumir éxito.</p>
 *
 * <p>Residuos por corrida (declarados): cada ejecución crea 2 publicaciones
 * aprobadas y 1 transacción {@code reservada} en la BD local, con
 * descripciones únicas {@code Date.now()} — no colisionan con corridas
 * anteriores ni con el seed, y no alteran el material de las siguientes. Las
 * transiciones de la transacción (enviar/entregar/confirmar/cancelar) NO se
 * ejecutan: están fuera del alcance de esta fila (solo se verifica que las
 * acciones de reservada estén disponibles para el vendedor).</p>
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

/** Sufijo único por corrida: aisla el material E2E de corridas anteriores y del seed. */
const sufijoCorrida = Date.now();

/** Descripción única de la publicación que se comprará con éxito (pasos 1-5). */
const descripcionExito = `Compra E2E TSK08 éxito ${sufijoCorrida}`;

/** Descripción única de la publicación con la que se fallará el pago (pasos 6-7). */
const descripcionFallo = `Compra E2E TSK08 fallo ${sufijoCorrida}`;

/** Precio en soles ingresado en la UI (se convierte a centavos en la frontera HTTP): 99.99 = 9999 centavos. */
const precioSoles = '99.99';

/** Precio esperado en la UI de transacciones (snapshot en centavos formateado a soles). */
const precioFormateado = 'S/ 99.99';

/** Stock entero ≥ 1 exigido por Story 1; se asevera el decremento 2 → 1 tras la reserva. */
const stock = '2';

/** Categoría del seed usada para clasificar las publicaciones de la precondición (Story 1). */
const categoriaSeed = 'Electrónica';

/** Subcategoría del seed de la categoría elegida (existe en R__seed_demo.sql). */
const subcategoriaSeed = 'Auriculares';

/** Credenciales documentadas del vendedor de demostración (R__seed_demo.sql, PHA06TSK08). */
const emailVendedorDemo = 'vendedor@easymarket.dev';
const contrasenaVendedorDemo = 'VendedorPass123!';

/** Credenciales documentadas del comprador de demostración (R__seed_demo.sql, PHA06TSK08). */
const emailCompradorDemo = 'comprador@easymarket.dev';
const contrasenaCompradorDemo = 'CompradorPass123!';

/** Tarjeta de prueba exitosa de Stripe test mode (sin espacios; Stripe formatea solo). */
const tarjetaExitosa = '4242424242424242';

/** Tarjeta de prueba declinada genérica de Stripe test mode. */
const tarjetaDeclinada = '4000000000000002';

/** Fecha de expiración futura aceptada por Stripe (diciembre 2034). */
const expiracionFutura = '12/34';

/** CVC de prueba aceptado por Stripe test mode. */
const cvcPrueba = '123';

/** Tiempo máximo de espera del webhook {@code payment_intent.succeeded} (Stripe → CLI → backend). */
const TIMEOUT_WEBHOOK_MS = 60_000;

/** Intervalo entre recargas del polling de /compras mientras se espera el webhook. */
const INTERVALO_POLL_MS = 3_000;

/** Única página compartida por todo el recorrido (conserva cookie y navegación). */
let pagina: Page;

/** ID real de la publicación de éxito, capturado de la URL del detalle en el paso 3. */
let idPublicacionExito: number;

/** ID real de la publicación de fallo, capturado de la URL del detalle en el paso 6. */
let idPublicacionFallo: number;

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

/**
 * Inicia sesión con las credenciales dadas y espera la redirección canónica.
 *
 * <p>Endpoints reales ejercitados indirectamente: {@code POST /auth/login}
 * (PHA01TSK07) que setea la cookie httpOnly {@code jwt}; la redirección a
 * {@code /publicaciones} es la decisión documentada de plan.md (navegación
 * frontend). Se usa en cada cambio de sesión del recorrido.</p>
 *
 * @param email email del usuario demo o ADMIN
 * @param contrasena contraseña correspondiente (nunca se imprime)
 * @returns promesa resuelta cuando la sesión quedó establecida en /publicaciones
 */
async function iniciarSesion(email: string, contrasena: string): Promise<void> {
  await pagina.goto('/auth');
  await pagina.getByRole('tab', { name: 'Iniciar sesión' }).click();
  await pagina.getByLabel('Email').fill(email);
  await pagina.getByLabel('Contraseña').fill(contrasena);
  await pagina.getByRole('button', { name: 'Ingresar' }).click();
  await expect(pagina).toHaveURL(/\/publicaciones$/);
}

/**
 * Cierra la sesión activa y espera la redirección a /auth.
 *
 * <p>Endpoint real ejercitado indirectamente: {@code POST /auth/logout}
 * (PHA07TSK03); la cookie httpOnly expira por {@code Set-Cookie} del backend.
 * Se usa en cada cambio de sesión del recorrido para dejar el navegador limpio
 * antes del login del siguiente actor. Primero navega a {@code /compras}, ruta
 * con shell para cualquier rol autenticado: el botón "Cerrar sesión" vive en el
 * shell, que no se materializa en rutas públicas como {@code /publicaciones/{id}}
 * (fallo real observado en la corrida focal L01 cuando el paso previo termina en
 * el detalle de una publicación).</p>
 *
 * @returns promesa resuelta cuando el shell redirigió a /auth
 */
async function cerrarSesionActual(): Promise<void> {
  await pagina.goto('/compras');
  await abrirMenu();
  await pagina.getByRole('button', { name: 'Cerrar sesión' }).click();
  await expect(pagina).toHaveURL(/\/auth$/);
}

/**
 * Crea una publicación única real desde el formulario /publicar (Story 1).
 *
 * <p>Endpoints reales ejercitados indirectamente: {@code GET /categorias}
 * (pobla los selects) y {@code POST /publicaciones} (creación en
 * PHA06TSK02/PHA02TSK09). Tras crear, la app redirige a
 * {@code /mis-publicaciones}. La descripción única por corrida garantiza que
 * la moderación del paso 2 y las verificaciones de los pasos 4-7 operen sobre
 * material propio.</p>
 *
 * @param descripcion descripción única de la publicación a crear
 * @returns promesa resuelta cuando la publicación quedó creada en PENDIENTE_REVISION
 */
async function crearPublicacion(descripcion: string): Promise<void> {
  await abrirMenu();
  await navegarConReintento(
    pagina.getByRole('link', { name: 'Publicar', exact: true }),
    /\/publicar$/
  );
  const categoriaSelect = pagina.getByLabel('Categoría', { exact: true });
  const subcategoriaSelect = pagina.getByLabel('Subcategoría', { exact: true });
  // Espera la carga REAL del catálogo (el select se habilita tras GET /categorias).
  await expect(categoriaSelect).toBeEnabled();
  await categoriaSelect.selectOption({ label: categoriaSeed });
  await expect(subcategoriaSelect).toBeEnabled();
  await subcategoriaSelect.selectOption({ label: subcategoriaSeed });
  await pagina.getByLabel('Precio (S/)').fill(precioSoles);
  await pagina.getByLabel('Stock').fill(stock);
  await pagina.getByLabel('Descripción').fill(descripcion);
  await pagina.getByRole('button', { name: 'Publicar' }).click();
  // Redirección canónica post-creación a la fuente de las publicaciones propias.
  await expect(pagina).toHaveURL(/\/mis-publicaciones$/);
}

/**
 * Aprueba en la moderación la publicación identificada por su descripción única.
 *
 * <p>Endpoint real ejercitado indirectamente: {@code POST /publicaciones/{id}/moderar}
 * (PHA06TSK13). Se modera EXCLUSIVAMENTE el material de este recorrido (por su
 * descripción {@code Date.now()}), nunca las del seed: cada corrida es
 * re-ejecutable y no altera el material de las siguientes. El texto
 * "Publicación moderada exitosamente" solo aparece tras el 200 real.</p>
 *
 * @param descripcion descripción única de la publicación a aprobar
 * @returns promesa resuelta cuando la publicación salió de la lista de pendientes
 */
async function aprobarPublicacion(descripcion: string): Promise<void> {
  const tarjeta = pagina.locator('div.space-y-4').filter({
    has: pagina.getByText(descripcion)
  });
  await expect(tarjeta).toHaveCount(1);
  await tarjeta.getByRole('button', { name: 'Aprobar' }).click();
  await expect(pagina.getByText('Publicación moderada exitosamente')).toBeVisible();
  // La publicación aprobada sale de la lista de pendientes (estado real APROBADA).
  await expect(pagina.getByText(descripcion)).toHaveCount(0);
}

/**
 * Localiza la sección de compra del CompraButton (PHA03TSK11).
 *
 * <p>La sección contiene el heading {@code h2} "Escrow EasyMarket". Acotar los
 * banners {@code role="status"}/{@code role="alert"} a esta sección evita
 * colisionar con el route announcer de Next.js (que también usa
 * {@code role="status"} y {@code role="alert"} según el momento de la
 * navegación, fenómeno documentado en PHA07TSK07).</p>
 *
 * @returns locator de la {@code section} del formulario de compra
 */
function seccionCompra(): Locator {
  return pagina.locator('section').filter({
    has: pagina.getByRole('heading', { level: 2, name: 'Escrow EasyMarket' })
  });
}

/**
 * Llena el PaymentElement embebido de Stripe con los datos de una tarjeta de prueba.
 *
 * <p>El PaymentElement renderiza sus campos dentro de un iframe cross-origin de
 * {@code js.stripe.com}. Iteración empírica de selectores (documentada en el
 * Artifact PHA07TSK08-L01): el primer intento con
 * {@code iframe[title="Secure payment input frame"]} resolvió a DOS iframes
 * (strict mode violation) y el segundo con {@code iframe[src*="elements-inner-easel"]}
 * no expuso inputs con {@code name="cardnumber"}. El snapshot de accesibilidad
 * del error mostró la estructura definitiva: el div con
 * {@code aria-label="Formulario de pago Stripe"} contiene exactamente UN
 * iframe, y dentro los campos se exponen por su nombre accesible — textbox
 * {@code "Card number"}, {@code "Expiration date"} y {@code "Security code"}.
 * El selector definitivo acota el iframe al contenedor del PaymentElement y
 * llena por rol/textbox con nombre accesible (patrón robusto a cambios
 * internos de atributos de Stripe). El {@code fill} de Playwright espera al
 * frame y hace focus automático; Stripe formatea el número y la expiración
 * solos.</p>
 *
 * @param numeroTarjeta número de tarjeta de prueba (exitosa o declinada)
 * @param expiracion expiración futura en formato {@code MM/YY}
 * @param cvc CVC de prueba
 * @returns promesa resuelta cuando los tres campos quedaron llenos
 */
async function llenarFormularioPagoStripe(
  numeroTarjeta: string,
  expiracion: string,
  cvc: string
): Promise<void> {
  const contenedorPago = pagina.getByLabel('Formulario de pago Stripe');
  const marcoPago = contenedorPago.frameLocator('iframe');
  await marcoPago.getByRole('textbox', { name: 'Card number' }).fill(numeroTarjeta);
  await marcoPago.getByRole('textbox', { name: 'Expiration date' }).fill(expiracion);
  await marcoPago.getByRole('textbox', { name: 'Security code' }).fill(cvc);
}

/**
 * Espera — por recarga acotada de /compras — a que la transacción de la
 * descripción dada sea visible (webhook {@code payment_intent.succeeded}).
 *
 * <p>Cada iteración recarga {@code /compras} (GET real de
 * {@code /transacciones/compras} del comprador autenticado) y espera a que el
 * estado de carga desaparezca antes de contar; si la descripción aparece, la
 * transacción ya fue persistida por el backend. El timeout total acotado hace
 * que un webhook perdido falle con error real en lugar de asumir éxito.</p>
 *
 * @param descripcion descripción única de la transacción esperada
 * @param timeoutMs presupuesto total de espera en milisegundos (default 60 s)
 * @returns promesa resuelta cuando la transacción es visible en /compras
 * @throws Error si el presupuesto se agota sin ver la transacción
 */
async function esperarTransaccionEnCompras(
  descripcion: string,
  timeoutMs: number = TIMEOUT_WEBHOOK_MS
): Promise<void> {
  const inicio = Date.now();
  while (Date.now() - inicio < timeoutMs) {
    await pagina.goto('/compras');
    await expect(pagina.getByRole('heading', { level: 1, name: 'Compras' })).toBeVisible();
    // Espera el fin del estado de carga antes de contar (evita falso negativo).
    await expect(pagina.getByText('Cargando compras...')).toHaveCount(0, { timeout: 5_000 });
    if ((await pagina.getByText(descripcion, { exact: true }).count()) > 0) {
      return;
    }
    await pagina.waitForTimeout(INTERVALO_POLL_MS);
  }
  throw new Error(
    `Timeout esperando la transacción "${descripcion}" en /compras tras ${timeoutMs} ms`
  );
}

/**
 * Localiza la card de transacción (li) que contiene la descripción dada.
 *
 * <p>Cada card es un {@code li} con un {@code article} cuyo {@code h2} es la
 * descripción de la publicación (ver {@code ListadoTransacciones} en
 * TransaccionesUI.tsx). El filtro por heading aísla la card correcta sin
 * depender del orden de la lista real del backend.</p>
 *
 * @param descripcion descripción única de la transacción buscada
 * @returns locator del {@code li} que contiene esa transacción
 */
function tarjetaTransaccion(descripcion: string): Locator {
  return pagina.locator('li').filter({
    has: pagina.getByRole('heading', { level: 2, name: descripcion })
  });
}

test.describe.serial('Compra con escrow y Stripe sandbox real (PHA07TSK08)', () => {
  /**
   * Crea el contexto y la página compartidos del recorrido serial.
   *
   * <p>Se usa el fixture {@code browser} (worker-scoped) porque cada {@code test}
   * del grupo serial recibe una página fresca por defecto; compartir una única
   * instancia es lo que permite conservar la cookie httpOnly {@code jwt} y el
   * estado de navegación real entre los 7 pasos.</p>
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

  test('paso 1 — precondición vendedor: dos publicaciones únicas (éxito y fallo) y logout', async () => {
    // Endpoints reales ejercitados indirectamente: POST /auth/login (PHA01TSK07),
    // GET /categorias (catálogo del select), POST /publicaciones (creación en
    // PENDIENTE_REVISION; PHA02TSK09/PHA06TSK02) y POST /auth/logout (PHA07TSK03).
    // Crea el material real que el ADMIN moderará en el paso 2, con datos únicos
    // por corrida (Date.now()): una publicación para la compra exitosa y otra
    // para la compra fallida. NO se compran publicaciones del seed (PHA07TSK06
    // asevera su stock).
    await iniciarSesion(emailVendedorDemo, contrasenaVendedorDemo);
    await crearPublicacion(descripcionExito);
    await crearPublicacion(descripcionFallo);
    await cerrarSesionActual();
  });

  test('paso 2 — moderación ADMIN: ambas publicaciones aprobadas y logout', async () => {
    // Validación E2E del gate 9 del harness (PHA07TSK05): si ADMIN_EMAIL o
    // ADMIN_PASSWORD del .env raíz fueran inválidas, POST /auth/login falla,
    // no hay cookie jwt y la moderación jamás carga. El valor de la contraseña
    // se inyecta sin imprimirse jamás. POST /publicaciones/{id}/moderar real
    // (PHA06TSK13) sobre EXCLUSIVAMENTE el material de este recorrido.
    const envRaiz = leerEnvRaiz(ENV_RAIZ);
    const adminEmail = envRaiz['ADMIN_EMAIL'];
    const adminPassword = envRaiz['ADMIN_PASSWORD'];
    expect(adminEmail, 'ADMIN_EMAIL debe estar definido en el .env raíz (gate 9)').toBeTruthy();
    expect(adminPassword, 'ADMIN_PASSWORD debe estar definido en el .env raíz (gate 9)').toBeTruthy();
    await iniciarSesion(adminEmail, adminPassword);
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Moderación', exact: true }),
      /\/admin\/moderacion$/
    );
    await aprobarPublicacion(descripcionExito);
    await aprobarPublicacion(descripcionFallo);
    await cerrarSesionActual();
  });

  test('paso 3 — compra exitosa: PaymentElement real con la tarjeta de prueba 4242', async () => {
    // Timeout ampliado: Stripe.js se carga desde el CDN y el dev server compila
    // la ruta on-demand; el montaje del PaymentElement puede superar los 30s.
    test.setTimeout(180_000);
    // Endpoints reales ejercitados indirectamente: POST /auth/login (comprador),
    // GET /publicaciones (mercado) + GET /publicaciones/{id} (detalle; stock 2),
    // POST /compras con Idempotency-Key (crea el PaymentIntent real en Stripe
    // sandbox; PHA03TSK11/PHA03 flujo de compra) y confirmPayment de Stripe.js
    // con la tarjeta de prueba 4242 (Stories 5). El banner de éxito role="status"
    // solo se renderiza cuando Stripe confirmó el pago sin error.
    await iniciarSesion(emailCompradorDemo, contrasenaCompradorDemo);
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Mercado', exact: true }),
      /\/publicaciones$/
    );
    await navegarConReintento(
      pagina.getByRole('link', { name: `Ver detalle de ${descripcionExito}` }),
      /\/publicaciones\/\d+$/
    );
    const coincidenciaId = pagina.url().match(/\/publicaciones\/(\d+)$/);
    expect(coincidenciaId, 'la URL del detalle debe contener el ID de la publicación').not.toBeNull();
    idPublicacionExito = Number(coincidenciaId![1]);
    // Stock real antes de la reserva: la publicación propia con stock 2.
    await expect(pagina.getByText('Stock disponible: 2', { exact: true })).toBeVisible();
    await seccionCompra().getByRole('button', { name: 'Comprar', exact: true }).click();
    // Stripe.js carga on-demand: el PaymentElement se monta tras el clientSecret.
    await expect(pagina.getByLabel('Formulario de pago Stripe')).toBeVisible({ timeout: 60_000 });
    await expect(
      seccionCompra().getByRole('button', { name: 'Confirmar pago', exact: true })
    ).toBeEnabled({ timeout: 60_000 });
    await llenarFormularioPagoStripe(tarjetaExitosa, expiracionFutura, cvcPrueba);
    await seccionCompra().getByRole('button', { name: 'Confirmar pago', exact: true }).click();
    // Story 5: el banner de éxito es la confirmación real de Stripe (no un mock).
    await expect(seccionCompra().getByRole('status')).toContainText('Pago confirmado', {
      timeout: 60_000
    });
  });

  test('paso 4 — webhook: reserva en /compras con S/ 99.99 y stock decrementado a 1', async () => {
    // Cadena real completa: payment_intent.succeeded → Stripe CLI → backend
    // (ProcesadorEventosWebhookService) → transacción "reservada" + decremento
    // atómico de stock en la MISMA transacción de BD (plan.md flujo PHA03).
    // El polling por recarga de /compras (GET /transacciones/compras real) espera
    // el webhook; un timeout aquí es un fallo honesto de infraestructura.
    await esperarTransaccionEnCompras(descripcionExito);
    const card = tarjetaTransaccion(descripcionExito);
    // Snapshot inmutable del precio en centavos presentado a soles (principio 3).
    await expect(card.getByText(precioFormateado, { exact: true })).toBeVisible();
    // Estado canónico "reservada" (código minúsculo del DTO renderizado literal).
    await expect(card.getByText('reservada', { exact: true })).toBeVisible();
    // El detalle de la publicación propia muestra el stock decrementado 2 → 1.
    await pagina.goto(`/publicaciones/${idPublicacionExito}`);
    await expect(pagina.getByText('Stock disponible: 1', { exact: true })).toBeVisible();
  });

  test('paso 5 — vendedor: venta en /ventas con acciones de escrow y saldo no acreditado (Story 12)', async () => {
    // Timeout ampliado: el paso recorre ventas, detalle de venta y saldo con
    // compilación on-demand del dev server (varias rutas nuevas por corrida).
    test.setTimeout(180_000);
    // Endpoints reales ejercitados indirectamente: POST /auth/logout y
    // POST /auth/login (cambio a vendedor), GET /transacciones/ventas,
    // GET /transacciones/{id} + pertenencia (PHA06TSK06/PHA06TSK12),
    // GET /usuarios/me/saldo (PHA04TSK17). El panel en rol VENDEDOR con estado
    // reservada muestra "Marcar enviado" (Story 6a) y "Cancelar" con motivo
    // obligatorio (Story 7). NO se ejecutan transiciones (fuera de alcance).
    await cerrarSesionActual();
    await iniciarSesion(emailVendedorDemo, contrasenaVendedorDemo);
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Ventas', exact: true }),
      /\/ventas$/
    );
    await expect(pagina.getByRole('heading', { level: 1, name: 'Ventas' })).toBeVisible();
    await expect(pagina.getByText('Cargando ventas...')).toHaveCount(0);
    const card = tarjetaTransaccion(descripcionExito);
    await expect(card.getByText(precioFormateado, { exact: true })).toBeVisible();
    await expect(card.getByText('reservada', { exact: true })).toBeVisible();
    // Abre el detalle de la venta (rol VENDEDOR) y verifica las acciones de reservada.
    await navegarConReintento(
      card.getByRole('link', { name: `Ver venta ${descripcionExito}` }),
      /\/ventas\/\d+$/
    );
    await expect(pagina.getByRole('heading', { name: 'Progreso del escrow' })).toBeVisible();
    await expect(pagina.getByText('reservada', { exact: true })).toBeVisible();
    await expect(pagina.getByText(precioFormateado, { exact: true })).toBeVisible();
    await expect(pagina.getByRole('button', { name: 'Marcar enviado', exact: true })).toBeVisible();
    // Story 7: el botón de cancelación existe en reservada, deshabilitado sin motivo.
    await expect(pagina.getByRole('button', { name: 'Cancelar', exact: true })).toBeVisible();
    await expect(pagina.getByRole('button', { name: 'Cancelar', exact: true })).toBeDisabled();
    // Story 12: la reserva NO acredita saldo. El seed no crea movimientos y este
    // recorrido no ejecuta transiciones, por lo que el panel de saldo real del
    // vendedor sigue sin movimientos: si la transacción hubiera acreditado, el
    // texto vacío no existiría y el monto de la venta aparecería en el detalle.
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Saldo', exact: true }),
      /\/saldo$/
    );
    await expect(pagina.getByText('Cargando saldo...')).toHaveCount(0);
    await expect(pagina.getByText('Todavía no tienes movimientos de saldo.')).toBeVisible();
    await expect(pagina.getByText(precioFormateado, { exact: true })).toHaveCount(0);
    await cerrarSesionActual();
  });

  test('paso 6 — compra fallida: tarjeta declinada 4000 muestra error de Stripe sin reserva', async () => {
    // Timeout ampliado por la carga del PaymentElement desde el CDN de Stripe.
    test.setTimeout(180_000);
    // Endpoints reales ejercitados indirectamente: POST /auth/login (comprador),
    // GET /publicaciones/{id} (detalle de la publicación de fallo; stock 2),
    // POST /compras (PaymentIntent real) y confirmPayment con la tarjeta
    // DECLINADA 4000...0002: Stripe test mode rechaza el pago y devuelve error,
    // que el CompraButton muestra en el banner role="alert" (Stories 5). El
    // mensaje exacto de Stripe no se asevera (varía por motivo de rechazo);
    // se asevera el banner de error real dentro de la sección de compra.
    await iniciarSesion(emailCompradorDemo, contrasenaCompradorDemo);
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Mercado', exact: true }),
      /\/publicaciones$/
    );
    await navegarConReintento(
      pagina.getByRole('link', { name: `Ver detalle de ${descripcionFallo}` }),
      /\/publicaciones\/\d+$/
    );
    const coincidenciaId = pagina.url().match(/\/publicaciones\/(\d+)$/);
    expect(coincidenciaId, 'la URL del detalle debe contener el ID de la publicación').not.toBeNull();
    idPublicacionFallo = Number(coincidenciaId![1]);
    await expect(pagina.getByText('Stock disponible: 2', { exact: true })).toBeVisible();
    await seccionCompra().getByRole('button', { name: 'Comprar', exact: true }).click();
    await expect(pagina.getByLabel('Formulario de pago Stripe')).toBeVisible({ timeout: 60_000 });
    await expect(
      seccionCompra().getByRole('button', { name: 'Confirmar pago', exact: true })
    ).toBeEnabled({ timeout: 60_000 });
    await llenarFormularioPagoStripe(tarjetaDeclinada, expiracionFutura, cvcPrueba);
    await seccionCompra().getByRole('button', { name: 'Confirmar pago', exact: true }).click();
    // El banner de error de Stripe es la prueba real del rechazo de la tarjeta.
    await expect(seccionCompra().getByRole('alert')).toBeVisible({ timeout: 60_000 });
  });

  test('paso 7 — no-transacción: el fallo no genera compra ni venta ni consume stock', async () => {
    // Un pago DECLINADO jamás dispara payment_intent.succeeded, por lo que el
    // backend no crea transacción (GET /transacciones/compras y /ventas reales
    // del comprador y del vendedor no contienen la descripción de fallo) ni
    // decrementa stock (el detalle real de la publicación de fallo sigue en 2).
    await pagina.goto('/compras');
    await expect(pagina.getByRole('heading', { level: 1, name: 'Compras' })).toBeVisible();
    await expect(pagina.getByText('Cargando compras...')).toHaveCount(0);
    await expect(pagina.getByText(descripcionFallo, { exact: true })).toHaveCount(0);
    await cerrarSesionActual();
    await iniciarSesion(emailVendedorDemo, contrasenaVendedorDemo);
    await abrirMenu();
    await navegarConReintento(
      pagina.getByRole('link', { name: 'Ventas', exact: true }),
      /\/ventas$/
    );
    await expect(pagina.getByRole('heading', { level: 1, name: 'Ventas' })).toBeVisible();
    await expect(pagina.getByText('Cargando ventas...')).toHaveCount(0);
    await expect(pagina.getByText(descripcionFallo, { exact: true })).toHaveCount(0);
    // El stock real de la publicación de fallo no se consumió.
    await pagina.goto(`/publicaciones/${idPublicacionFallo}`);
    await expect(pagina.getByText('Stock disponible: 2', { exact: true })).toBeVisible();
  });
});