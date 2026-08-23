/**
 * Helpers compartidos de validación del entorno E2E de cierre (PHA07TSK05).
 *
 * <p>Este módulo concentra las verificaciones REALES del harness de cierre.
 * Cada gate ejecuta una comprobación determinística contra el entorno local
 * (red, procesos, archivos de configuración, git) y devuelve un
 * {@link ResultadoGate} con diagnóstico accionable y comando sugerido, sin
 * exponer jamás el valor de una clave.</p>
 *
 * <p>El mismo conjunto de gates lo consume tanto el {@code globalSetup} de
 * Playwright (fallo temprano con diagnóstico agregado) como el spec de
 * preparación {@code preparacion.spec.ts} (que ES el "Test E2E de
 * preparación" del criterio de aceptación de la fila). Compartir la lógica
 * evita que ambas piezas diverjan y garantiza que el spec asevere
 * exactamente lo que el harness validó.</p>
 *
 * <p>Contrato de alcance de secretos (declarado en el Artifact de la tarea):
 * el harness comprueba que los archivos {@code .env} raíz y
 * {@code frontend/.env.local} estén ignorados por Git, que ningún archivo
 * versionado contenga patrones de clave Stripe plausibles y que los valores
 * reales de clave nunca se impriman en ningún diagnóstico. No escanea los
 * logs de consola de los servicios (backend/frontend/Stripe CLI): esa parte
 * del criterio queda fuera del harness y se declara explícitamente.</p>
 */

import { execSync } from 'node:child_process';
import { createConnection } from 'node:net';
import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

/** Directorio de este módulo: {@code <repo>/frontend/src/e2e}. */
const E2E_DIR = __dirname;

/** Directorio raíz del frontend: {@code <repo>/frontend}. */
const FRONTEND_DIR = resolve(E2E_DIR, '..', '..');

/** Directorio raíz del repositorio (donde vive {@code .env} y {@code .git}). */
const REPO_ROOT = resolve(E2E_DIR, '..', '..', '..');

/** Archivo de secretos locales de la raíz del repositorio (ignorado por Git). */
const ENV_RAIZ = join(REPO_ROOT, '.env');

/** Archivo de variables locales del frontend (ignorado por Git). */
const ENV_LOCAL_FRONTEND = join(FRONTEND_DIR, '.env.local');

/** Puerta de enlace del backend (Spring Boot dev). */
const URL_BACKEND_HEALTH = 'http://localhost:8080/health';

/** Origen del frontend que el backend debe aceptar por CORS (FRONTEND_URL dev). */
const ORIGEN_FRONTEND = 'http://localhost:3000';

/** URL del frontend (webServer de Playwright o `npm run dev` manual). */
const URL_FRONTEND = 'http://localhost:3000';

/** URL del webhook de Stripe a la que debe reenviar el CLI. */
const URL_FORWARD_STRIPE = 'http://localhost:8080/webhooks/stripe';

/** Nombre del contenedor PostgreSQL local (docker-compose.yml, PHA00TSK03). */
const CONTENEDOR_POSTGRES = 'easymarket_postgres_dev';

/** Placeholders por defecto de `application-dev.yml` / `.env.example`. */
const PLACEHOLDER_WEBHOOK_SECRET = 'whsec_REPLACE_ME';
const PLACEHOLDER_PUBLISHABLE_KEY = 'pk_test_REPLACE_ME';

/** Umbral mínimo de longitud del cuerpo de una clave para considerarla plausible (evita mocks de test). */
const LONGITUD_MINIMA_CLAVE = 16;

/**
 * Resultado de una verificación individual del harness.
 *
 * @param ok `true` si el gate pasó; `false` si el entorno no cumple el criterio
 * @param gate identificador estable del gate (usado en reportes y diagnósticos)
 * @param detalle diagnóstico legible del resultado, sin valores de clave
 * @param comandoSugerido comando que el humano puede ejecutar para remediar el fallo (opcional)
 */
export interface ResultadoGate {
  ok: boolean;
  gate: string;
  detalle: string;
  comandoSugerido?: string;
}

/**
 * Convierte un error desconocido en un mensaje legible para diagnósticos.
 *
 * @param error valor capturado en un bloque {@code catch} (cualquier tipo)
 * @returns mensaje estable del error; si no es una instancia de {@link Error} se devuelve su representación textual
 */
function mensajeError(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

/**
 * Lee un archivo {@code .env} local (formato {@code NOMBRE=valor} por línea,
 * ignorando comentarios y líneas vacías) y devuelve sus variables como mapa.
 *
 * <p>No expone los valores: los consumidores solo consultan presencia y
 * comparan contra placeholders conocidos.</p>
 *
 * @param ruta ruta absoluta del archivo {@code .env}
 * @returns mapa {@code NOMBRE → valor}; si el archivo no existe, mapa vacío
 */
function leerEnvArchivo(ruta: string): Record<string, string> {
  const mapa: Record<string, string> = {};
  if (!existsSync(ruta)) {
    return mapa;
  }
  const contenido = readFileSync(ruta, 'utf8');
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
 * Determina si un valor de variable está "configurado": existe, no está vacío
 * y no coincide con ninguno de los placeholders por defecto.
 *
 * @param valor valor leído de la variable (puede ser `undefined`)
 * @param placeholders lista de valores placeholder conocidos que cuentan como "sin configurar"
 * @returns `true` si el valor es real (no vacío y distinto de todo placeholder)
 */
function esClaveConfigurada(valor: string | undefined, placeholders: readonly string[]): boolean {
  if (valor === undefined) {
    return false;
  }
  const recortado = valor.trim();
  if (recortado.length === 0) {
    return false;
  }
  return !placeholders.includes(recortado);
}

/**
 * Ejecuta un comando git en la raíz del repositorio y devuelve salida y código.
 *
 * @param argumentos argumentos del comando git (ej. {@code ['check-ignore', '.env']})
 * @returns resultado con éxito, salida estándar y salida de error
 */
function git(argumentos: string[]): { ok: boolean; salida: string; error: string } {
  try {
    const salida = execSync(
      `git ${argumentos.map((a) => JSON.stringify(a)).join(' ')}`,
      { cwd: REPO_ROOT, encoding: 'utf8', windowsHide: true, timeout: 20_000 },
    );
    return { ok: true, salida: String(salida), error: '' };
  } catch (error) {
    const fallo = error as { status?: number; stdout?: Buffer | string; stderr?: Buffer | string };
    return {
      ok: fallo.status === 0,
      salida: String(fallo.stdout ?? ''),
      error: String(fallo.stderr ?? mensajeError(error)),
    };
  }
}

/**
 * Gate 1 — PostgreSQL: comprueba que el puerto 5432 acepta conexiones TCP.
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export function verificarPostgres(): Promise<ResultadoGate> {
  return new Promise((resolver) => {
    const socket = createConnection({ host: '127.0.0.1', port: 5432 });
    socket.setTimeout(15_000);
    socket.once('connect', () => {
      socket.destroy();
      resolver({
        ok: true,
        gate: 'postgres',
        detalle: 'PostgreSQL acepta conexiones en 127.0.0.1:5432.',
      });
    });
    socket.once('timeout', () => {
      socket.destroy();
      resolver({
        ok: false,
        gate: 'postgres',
        detalle: 'Timeout al intentar conectar a PostgreSQL en 127.0.0.1:5432.',
        comandoSugerido: 'docker compose up -d postgres',
      });
    });
    socket.once('error', (error) => {
      socket.destroy();
      resolver({
        ok: false,
        gate: 'postgres',
        detalle: `No se puede conectar a PostgreSQL en 127.0.0.1:5432 (${mensajeError(error)}).`,
        comandoSugerido: 'docker compose up -d postgres',
      });
    });
  });
}

/**
 * Gate 2 — Backend dev: comprueba que {@code GET /health} responde 200 con
 * {@code status UP}, reintentando ante fallos de red transitorios.
 *
 * <p>Se usa {@code /health} (mapeado por {@code HealthController}) y no
 * {@code /actuator/health} porque {@code SecurityConfig} protege todo lo que
 * no está en {@code permitAll} ({@code anyRequest().authenticated()}), de
 * modo que {@code /actuator/health} no devuelve 200 sin sesión. El estado del
 * actuator se sondea de forma informativa para el diagnóstico.</p>
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export async function verificarBackend(): Promise<ResultadoGate> {
  let ultimoError = '';
  for (let intento = 0; intento < 3; intento += 1) {
    try {
      const respuesta = await fetch(URL_BACKEND_HEALTH, { signal: AbortSignal.timeout(20_000) });
      const cuerpo = await respuesta.text();
      const detalleBase = `GET /health → ${respuesta.status} con cuerpo ${cuerpo.slice(0, 120)}.`;
      if (respuesta.status === 200 && cuerpo.includes('UP')) {
        let detalle = detalleBase;
        try {
          const actuator = await fetch('http://localhost:8080/actuator/health', {
            signal: AbortSignal.timeout(10_000),
          });
          detalle += ` GET /actuator/health → ${actuator.status} (protegido por SecurityConfig; informativo, no es el gate).`;
        } catch {
          detalle += ' GET /actuator/health → sin respuesta (informativo).';
        }
        return { ok: true, gate: 'backend', detalle };
      }
      return {
        ok: false,
        gate: 'backend',
        detalle: `${detalleBase} No cumple el contrato esperado (200 + "UP").`,
        comandoSugerido: 'cd backend && mvn spring-boot:run',
      };
    } catch (error) {
      ultimoError = mensajeError(error);
      if (intento < 2) {
        await new Promise((resolver) => setTimeout(resolver, 2_000));
      }
    }
  }
  return {
    ok: false,
    gate: 'backend',
    detalle: `No hay backend respondiendo en ${URL_BACKEND_HEALTH} (${ultimoError}).`,
    comandoSugerido: 'cd backend && mvn spring-boot:run',
  };
}

/**
 * Gate 3 — Perfil dev activo + cuentas demo: comprueba que el seed
 * demostrativo está presente en la BD local.
 *
 * <p>El seed {@code R__seed_demo.sql} se carga exclusivamente con el perfil
 * {@code dev} ({@code application-dev.yml} agrega {@code classpath:db/dev};
 * {@code application-prod.yml} no lo hace — verificado por PHA07TSK01). Por
 * tanto, la presencia de las dos cuentas demo
 * ({@code vendedor@easymarket.dev}, {@code comprador@easymarket.dev}) en la
 * BD local es una señal determinística y sin secretos de que el backend
 * activo usa el perfil {@code dev}. Combinado con el gate de salud, valida
 * "perfiles y CORS coinciden" del criterio.</p>
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export function verificarPerfilDev(): ResultadoGate {
  const sql =
    "SELECT COUNT(*) FROM usuarios WHERE email IN ('vendedor@easymarket.dev','comprador@easymarket.dev')";
  try {
    const salida = execSync(
      `docker exec ${CONTENEDOR_POSTGRES} psql -U easymarket -d easymarket_dev -tAc "${sql}"`,
      { encoding: 'utf8', windowsHide: true, timeout: 20_000 },
    );
    const cantidad = parseInt(String(salida).trim(), 10);
    if (cantidad === 2) {
      return {
        ok: true,
        gate: 'perfil-dev',
        detalle:
          'Seed demo presente en la BD local (2 cuentas demo: vendedor@easymarket.dev y comprador@easymarket.dev) — el seed solo carga con perfil dev, luego el backend activo usa dev.',
      };
    }
    return {
      ok: false,
      gate: 'perfil-dev',
      detalle: `Seed demo incompleto: se encontraron ${cantidad} de 2 cuentas demo en la BD local.`,
      comandoSugerido:
        'Reiniciar el backend con perfil dev para que Flyway ejecute R__seed_demo.sql',
    };
  } catch (error) {
    return {
      ok: false,
      gate: 'perfil-dev',
      detalle: `No se pudo consultar la BD local vía docker exec (${mensajeError(error)}).`,
      comandoSugerido: `docker compose up -d postgres (contenedor ${CONTENEDOR_POSTGRES})`,
    };
  }
}

/**
 * Gate 4 — CORS: comprueba que el backend acepta el origen del frontend.
 *
 * <p>Se envía un {@code GET /health} con header {@code Origin:
 * http://localhost:3000} y se verifica que la respuesta incluya
 * {@code Access-Control-Allow-Origin: http://localhost:3000}. La petición
 * atraviesa el {@code CorsFilter} real del {@code SecurityConfig} (que usa
 * {@code app.cors.allowed-origins}, {@code FRONTEND_URL} en dev), por lo que
 * la verificación ejerce la configuración efectiva, no su lectura.</p>
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export async function verificarCors(): Promise<ResultadoGate> {
  try {
    const respuesta = await fetch(URL_BACKEND_HEALTH, {
      headers: { Origin: ORIGEN_FRONTEND },
      signal: AbortSignal.timeout(20_000),
    });
    const permitido = respuesta.headers.get('access-control-allow-origin');
    if (permitido === ORIGEN_FRONTEND) {
      return {
        ok: true,
        gate: 'cors',
        detalle: `GET /health con Origin ${ORIGEN_FRONTEND} devuelve Access-Control-Allow-Origin: ${ORIGEN_FRONTEND} (CorsFilter del SecurityConfig real).`,
      };
    }
    return {
      ok: false,
      gate: 'cors',
      detalle: `La respuesta de /health no incluye el origen del frontend en Access-Control-Allow-Origin (valor actual: ${permitido ?? 'ausente'}).`,
      comandoSugerido: 'Verificar app.cors.allowed-origins del backend (FRONTEND_URL=http://localhost:3000)',
    };
  } catch (error) {
    return {
      ok: false,
      gate: 'cors',
      detalle: `No se pudo comprobar CORS contra el backend (${mensajeError(error)}).`,
      comandoSugerido: 'cd backend && mvn spring-boot:run',
    };
  }
}

/**
 * Gate 5 — Frontend: comprueba que {@code http://localhost:3000} responde HTTP.
 *
 * <p>En la ejecución vía Playwright, el {@code webServer} de la configuración
 * levanta (o reutiliza) el frontend antes del {@code globalSetup}; en una
 * ejecución manual, el humano debe tener {@code npm run dev} activo.</p>
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export async function verificarFrontend(): Promise<ResultadoGate> {
  try {
    const respuesta = await fetch(URL_FRONTEND, { signal: AbortSignal.timeout(30_000) });
    if (respuesta.ok) {
      return {
        ok: true,
        gate: 'frontend',
        detalle: `Frontend responde HTTP ${respuesta.status} en ${URL_FRONTEND}.`,
      };
    }
    return {
      ok: false,
      gate: 'frontend',
      detalle: `Frontend responde HTTP ${respuesta.status} (no-2xx) en ${URL_FRONTEND}.`,
      comandoSugerido: 'cd frontend && npm run dev',
    };
  } catch (error) {
    return {
      ok: false,
      gate: 'frontend',
      detalle: `No hay frontend respondiendo en ${URL_FRONTEND} (${mensajeError(error)}).`,
      comandoSugerido: 'cd frontend && npm run dev',
    };
  }
}

/**
 * Gate 6 — Stripe CLI reenviando: detecta un proceso {@code stripe listen}
 * cuyo destino sea exactamente {@code --forward-to http://localhost:8080/webhooks/stripe}.
 *
 * <p>En Windows se inspecciona la línea de comandos de cada proceso
 * {@code stripe.exe} vía WMI/CIM. Se exige que la línea contenga {@code listen},
 * {@code --forward-to} y la URL del webhook del backend para evitar falsos
 * positivos (un {@code stripe.exe} dedicado a {@code trigger} u otro
 * {@code listen} con distinto destino no cuenta).</p>
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export function verificarStripeCli(): ResultadoGate {
  const comandoCim =
    'powershell -NoProfile -Command "Get-CimInstance Win32_Process | ' +
    "Where-Object { $_.Name -eq 'stripe.exe' } | ForEach-Object { $_.CommandLine }\"";
  let salida = '';
  try {
    salida = String(
      execSync(comandoCim, { encoding: 'utf8', windowsHide: true, timeout: 20_000 }),
    );
  } catch (error) {
    return {
      ok: false,
      gate: 'stripe-cli',
      detalle: `No se pudo inspeccionar los procesos stripe.exe (${mensajeError(error)}).`,
      comandoSugerido: `stripe listen --forward-to ${URL_FORWARD_STRIPE}`,
    };
  }
  const procesos = salida
    .split(/\r?\n/)
    .map((linea) => linea.trim())
    .filter((linea) => linea.length > 0);
  const reenviando = procesos.some(
    (linea) =>
      linea.includes('listen') &&
      linea.includes('--forward-to') &&
      linea.includes(URL_FORWARD_STRIPE),
  );
  if (reenviando) {
    return {
      ok: true,
      gate: 'stripe-cli',
      detalle: `Proceso 'stripe listen --forward-to ${URL_FORWARD_STRIPE}' activo (${procesos.length} proceso(s) stripe.exe detectado(s)).`,
    };
  }
  return {
    ok: false,
    gate: 'stripe-cli',
    detalle: `No se detectó un proceso 'stripe listen --forward-to ${URL_FORWARD_STRIPE}' activo (${procesos.length} proceso(s) stripe.exe encontrado(s), ninguno con ese comando).`,
    comandoSugerido: `stripe listen --forward-to ${URL_FORWARD_STRIPE}`,
  };
}

/**
 * Gate 7 — Webhook secret del backend: comprueba que {@code STRIPE_WEBHOOK_SECRET}
 * esté definido y no sea el placeholder en el {@code .env} raíz (ignorado por Git).
 *
 * <p>El contrato local documentado es que las claves Stripe viven en el
 * {@code .env} raíz, que el desarrollador carga en el entorno del proceso
 * backend antes de {@code mvn spring-boot:run}. El harness verifica esa
 * fuente de verdad local; no puede leer el entorno de otro proceso en
 * Windows (limitación declarada en el Artifact).</p>
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export function verificarWebhookSecretBackend(): ResultadoGate {
  const variables = leerEnvArchivo(ENV_RAIZ);
  const valor = variables['STRIPE_WEBHOOK_SECRET'];
  if (esClaveConfigurada(valor, [PLACEHOLDER_WEBHOOK_SECRET])) {
    return {
      ok: true,
      gate: 'webhook-secret',
      detalle:
        'STRIPE_WEBHOOK_SECRET está definida y no es el placeholder en .env raíz (archivo ignorado por Git).',
    };
  }
  return {
    ok: false,
    gate: 'webhook-secret',
    detalle:
      'STRIPE_WEBHOOK_SECRET falta o es el placeholder whsec_REPLACE_ME en .env raíz.',
    comandoSugerido: `Ejecutar 'stripe listen --forward-to ${URL_FORWARD_STRIPE}' y copiar el whsec_ impreso a STRIPE_WEBHOOK_SECRET en el .env raíz`,
  };
}

/**
 * Gate 8 — Publishable key del frontend: comprueba que
 * {@code NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY} esté definida y no sea el
 * placeholder en {@code frontend/.env.local} (ignorado por Git).
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export function verificarPublishableKeyFrontend(): ResultadoGate {
  const variables = leerEnvArchivo(ENV_LOCAL_FRONTEND);
  const valor = variables['NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY'];
  if (esClaveConfigurada(valor, [PLACEHOLDER_PUBLISHABLE_KEY])) {
    return {
      ok: true,
      gate: 'publishable-key',
      detalle:
        'NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY está definida y no es el placeholder en frontend/.env.local (archivo ignorado por Git).',
    };
  }
  return {
    ok: false,
    gate: 'publishable-key',
    detalle:
      'NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY falta o es el placeholder pk_test_REPLACE_ME en frontend/.env.local.',
    comandoSugerido:
      'Copiar la publishable key de test del Dashboard de Stripe a NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY en frontend/.env.local',
  };
}

/**
 * Gate 9 — Variables ADMIN locales: comprueba que {@code ADMIN_EMAIL} y
 * {@code ADMIN_PASSWORD} estén definidas y no vacías en el {@code .env} raíz.
 *
 * <p>Alcance estricto de TSK05: solo "definidas y no vacías". La validación
 * de credenciales del ADMIN es responsabilidad de PHA07TSK07. Nunca se
 * imprime el valor de la contraseña.</p>
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export function verificarAdminVariables(): ResultadoGate {
  const variables = leerEnvArchivo(ENV_RAIZ);
  const email = variables['ADMIN_EMAIL'];
  const contrasena = variables['ADMIN_PASSWORD'];
  const emailOk = esClaveConfigurada(email, []);
  const contrasenaOk = esClaveConfigurada(contrasena, []);
  if (emailOk && contrasenaOk) {
    return {
      ok: true,
      gate: 'admin-variables',
      detalle:
        'ADMIN_EMAIL y ADMIN_PASSWORD definidas y no vacías en .env raíz (ignorado por Git). Validación de credenciales: PHA07TSK07.',
    };
  }
  const faltantes: string[] = [];
  if (!emailOk) {
    faltantes.push('ADMIN_EMAIL');
  }
  if (!contrasenaOk) {
    faltantes.push('ADMIN_PASSWORD');
  }
  return {
    ok: false,
    gate: 'admin-variables',
    detalle: `Variables ADMIN no definidas o vacías en .env raíz: ${faltantes.join(', ')}. TSK05 solo valida definición; la validación de credenciales es de PHA07TSK07.`,
    comandoSugerido: 'Agregar ADMIN_EMAIL y ADMIN_PASSWORD al .env raíz (ignorado por Git)',
  };
}

/**
 * Gate 10 — Secretos ausentes del repositorio: comprueba que los archivos de
 * entorno reales estén ignorados por Git y que ningún archivo versionado
 * contenga patrones de clave Stripe plausibles.
 *
 * <p>Alcance declarado: se verifican (a) {@code git check-ignore} sobre
 * {@code .env} y {@code frontend/.env.local}; (b) {@code git ls-files} +
 * escaneo de contenido de archivos versionados con patrones de clave
 * {@code sk_}, {@code whsec_}, {@code pk_}, {@code rk_} de cuerpo plausible
 * (≥ 16 caracteres alfanuméricos). Los placeholders {@code *_REPLACE_ME} y
 * los mocks deterministas de test (ej. {@code pk_test_deterministic}, 13
 * caracteres) no son secretos y no cuentan como hallazgo. No se escanean los
 * logs de consola de los servicios (fuera del alcance del harness).</p>
 *
 * @returns resultado del gate con diagnóstico y comando sugerido
 */
export function verificarSecretosRepo(): ResultadoGate {
  const errores: string[] = [];

  const ignorados = ['.env', 'frontend/.env.local'];
  for (const ruta of ignorados) {
    const resultado = git(['check-ignore', ruta]);
    if (!resultado.ok || resultado.salida.trim().length === 0) {
      errores.push(`${ruta} NO está ignorado por Git`);
    }
  }

  const listado = git(['ls-files']);
  if (!listado.ok) {
    return {
      ok: false,
      gate: 'secretos-repo',
      detalle: `No se pudo ejecutar git ls-files: ${listado.error}`,
      comandoSugerido: 'Verificar que el repositorio sea un clon git válido',
    };
  }
  const patronClave = new RegExp(
    `(sk_(?:test|live)_(?!REPLACE_ME)[A-Za-z0-9]{${LONGITUD_MINIMA_CLAVE},}|` +
      `whsec_(?!REPLACE_ME)[A-Za-z0-9]{${LONGITUD_MINIMA_CLAVE},}|` +
      `pk_(?:test|live)_(?!REPLACE_ME)[A-Za-z0-9]{${LONGITUD_MINIMA_CLAVE},}|` +
      `rk_(?:test|live)_(?!REPLACE_ME)[A-Za-z0-9]{${LONGITUD_MINIMA_CLAVE},})`,
    'g',
  );
  const hallazgos: string[] = [];
  for (const ruta of listado.salida.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)) {
    const rutaAbsoluta = join(REPO_ROOT, ...ruta.split('/'));
    let contenido: string;
    try {
      contenido = readFileSync(rutaAbsoluta, 'utf8');
    } catch {
      continue;
    }
    const coincidencias = contenido.match(patronClave);
    if (coincidencias && coincidencias.length > 0) {
      hallazgos.push(`${ruta} (${coincidencias.length})`);
    }
  }
  if (hallazgos.length > 0) {
    errores.push(`Archivos versionados con patrones de clave plausible: ${hallazgos.join(', ')}`);
  }

  if (errores.length > 0) {
    return {
      ok: false,
      gate: 'secretos-repo',
      detalle: `${errores.join('; ')}.`,
      comandoSugerido: 'Mover cualquier valor real de clave a archivos .env ignorados por Git',
    };
  }
  return {
    ok: true,
    gate: 'secretos-repo',
    detalle:
      '.env y frontend/.env.local están ignorados por Git (git check-ignore) y ningún archivo versionado contiene patrones de clave Stripe plausibles.',
  };
}

/**
 * Ejecuta los diez gates del harness en paralelo y devuelve los resultados en
 * el orden estable (postgres, backend, perfil-dev, cors, frontend, stripe-cli,
 * webhook-secret, publishable-key, admin-variables, secretos-repo).
 *
 * @returns promesa con los resultados de todos los gates
 */
export async function verificarEntornoCompleto(): Promise<ResultadoGate[]> {
  return Promise.all([
    verificarPostgres(),
    verificarBackend(),
    verificarPerfilDev(),
    verificarCors(),
    verificarFrontend(),
    verificarStripeCli(),
    verificarWebhookSecretBackend(),
    verificarPublishableKeyFrontend(),
    verificarAdminVariables(),
    verificarSecretosRepo(),
  ]);
}

/**
 * Construye el mensaje legible de un resultado de gate para reportes.
 *
 * @param resultado resultado del gate
 * @returns texto con gate, detalle y comando sugerido (si existe)
 */
export function textoResultado(resultado: ResultadoGate): string {
  const sugerido = resultado.comandoSugerido ? `\n    Comando sugerido: ${resultado.comandoSugerido}` : '';
  return `  ${resultado.ok ? '✓' : '✗'} ${resultado.gate}: ${resultado.detalle}${sugerido}`;
}
