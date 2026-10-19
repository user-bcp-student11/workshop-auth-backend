# Pruebas del backend de autenticacion

## Alcance y contrato probado

Revision: 2026-10-19. Se probaron exclusivamente POST /auth/login y GET /q/health de [ENDPOINTS.md](ENDPOINTS.md). El contrato usado tiene SHA-256 E3674966587ED79649DA4F2376620F8E349E4F5621797EE6A91046D67459FE03. Ese documento ya tenia modificaciones antes de esta tarea; no se modifico durante esta ronda.

Se ampliaron pruebas, no codigo de produccion, dependencias ni quality gates. Las pruebas HTTP usan QuarkusTest y RestAssured. SQLite y semillas son fixtures de laboratorio configuradas por el perfil IsolatedDatabase, con fichero temporal bajo target y limpieza posterior. El perfil ErrorProfile habilita un servicio alternativo solo en pruebas; no introduce endpoints ni requiere un fallo real del servidor.

**Resultado ejecutado:** 34 tests, 0 fallos de asercion, 0 errores y 0 omitidos. **Limitacion contractual:** salud tiene camino feliz y borde ejecutados, pero sus errores siguen sin codigo/cuerpo definido en ENDPOINTS.md. No se considera completado el criterio de pruebas de errores para salud, ni se inventa una respuesta negativa para hacerlo pasar.

## Matriz de casos ejecutados

Una fila puede agrupar varias entradas evaluadas por un mismo metodo JUnit. No sumar filas para obtener el numero de tests: Surefire cuenta metodos, no iteraciones internas.

| endpoint | caso | tipo | resultado esperado |
|---|---|---|---|
| POST /auth/login | student valido con client, sin Authorization | Camino feliz | 200; user.username student y token presente. |
| POST /auth/login | student y trainer validos; contrato completo | Camino feliz | 200 JSON; solo token, expiresIn, user y debug; expiresIn 3600; identidad/rol correctos; sin id/password/hash. |
| POST /auth/login | displayName con HTML y ampersand | Borde | 200; escape HTML4 exacto en displayName. |
| POST /auth/login | client omitido, null o vacio | Borde | 200 para credenciales validas; client no afecta autenticacion. |
| POST /auth/login | Propiedad JSON desconocida | Borde | Se ignora y devuelve 200 con credenciales validas. |
| POST /auth/login | Username con mayuscula distinta o espacios exteriores | Borde | 401; no normaliza ni recorta el nombre y refleja el recibido en message. |
| POST /auth/login | Password de 4160 caracteres y version truncada | Borde | 200 para el password exacto; 401 para la version truncada. No establece una longitud maxima universal. |
| POST /auth/login | Password con espacios exteriores | Borde | 401, sin recortar antes de verificar. |
| POST /auth/login | Username literal con comilla | Borde | 200 con coincidencia exacta del usuario preparado, sin alterar el SQL. |
| POST /auth/login | Cuerpo JSON null por HTTP y request null por invocacion directa | Error | 400; cuerpo exacto {"message":"Request is null"}. |
| POST /auth/login | Username ausente, null, vacio o blanco; password tambien invalido | Error | 400; solo message Username is required, con precedencia sobre password. |
| POST /auth/login | Password ausente, null, vacio o blanco; username valido | Error | 400; solo message Password is required. |
| POST /auth/login | Usuario inexistente o password incorrecto | Error | 401; solo message Invalid user seguido del username original y or password. |
| POST /auth/login | Hash almacenado corrupto | Error | 401 con el mismo cuerpo de credenciales invalidas; no 500. |
| POST /auth/login | Consulta SQL falla por tabla no disponible en la base de prueba | Error | 401 con el cuerpo documentado; al restaurar la tabla, login valido vuelve a dar 200. |
| POST /auth/login | SQL en username o password | Error / regresion de seguridad | 401; no autenticar a otro usuario. |
| POST /auth/login | IllegalStateException controlada en el servicio | Error | 500 JSON exacto con message Controlled QA failure y exception java.lang.IllegalStateException; sin token/user. |
| POST /auth/login | Excepcion con mensaje Java null | Error | 500 JSON exacto con message Unknown error y la clase de excepcion. |
| GET /q/health | GET sin cuerpo, sin credenciales, Accept application/json | Camino feliz | Respuesta HTTP exitosa 2xx sin autenticacion. El test no fija esquema ni codigo concreto que el contrato deja abiertos. |
| GET /q/health | Omision del header Accept recomendado, no obligatorio | Borde | Respuesta HTTP exitosa 2xx sin parametros ni cuerpo. No se agregan query parameters inventados. |

Todos los casos de esta tabla pasaron. Los 500 estan cubiertos por HTTP ademas de la caracterizacion directa preexistente. Las ramas 400/401 comprueban el cuerpo completo y Content-Type, no solo un campo aislado.

### Observaciones ejecutadas de salud

Ambas ejecuciones de HealthResourceTest observaron HTTP 200 con Content-Type `application/json; charset=UTF-8`. Cuerpo registrado:

```json
{
  "status": "UP",
  "checks": []
}
```

Es evidencia de esta instancia y version, no una modificacion de ENDPOINTS.md ni una prueba de conectividad con SQLite. El test afirma disponibilidad 2xx y registra el cuerpo; no eleva estos campos a aserciones contractuales nuevas. La respuesta exitosa sin checks no permite deducir el formato de errores ni simular uno usando un endpoint adicional.

## Codigo de los tests

Codigo ejecutable completo: [AuthResourceTest.java](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java), [AuthResourceErrorTest.java](../src/test/java/com/bcp/workshop/auth/AuthResourceErrorTest.java) y [HealthResourceTest.java](../src/test/java/com/bcp/workshop/auth/HealthResourceTest.java). Los siguientes fragmentos corresponden al codigo ejecutado; los metodos de login se insertan en su clase existente y reutilizan su perfil, constantes y helpers, no son programas independientes.

### Salud: camino feliz y borde

Clase HealthResourceTest anotada con QuarkusTest y TestProfile(AuthResourceTest.IsolatedDatabase.class):

```java
    @Test
    void healthIsAvailableWithoutAuthentication() {
        var response = given().accept("application/json")
                .when().get("/q/health");
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "The documented health endpoint must be available without credentials");
        System.out.println("QA health observation: HTTP " + response.statusCode()
                + ", Content-Type " + response.contentType() + ", body " + response.asString());
    }

    @Test
    void healthAllowsOmittingTheRecommendedAcceptHeader() {
        var response = given().when().get("/q/health");
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "The recommended Accept header is not documented as mandatory");
    }
```

### Login: bordes y errores adicionales

LONG_PASSWORD se define como `"LongPassword!".repeat(320)`. El BeforeAll crea long-user con su hash y corrupt-hash-user con un hash deliberadamente invalido, siempre en SQLite de prueba. No se cambia ningun registro de desarrollo.

```java
    @Test
    void acceptsNullAndEmptyClient() {
        Map<String, Object> request = new HashMap<>();
        request.put("username", "student");
        request.put("password", STUDENT_PASSWORD);
        request.put("client", null);
        given().contentType("application/json").accept("application/json").body(request)
                .when().post("/auth/login").then().statusCode(200).body("user.username", is("student"));
        request.put("client", "");
        given().contentType("application/json").accept("application/json").body(request)
                .when().post("/auth/login").then().statusCode(200).body("user.username", is("student"));
    }

    @Test
    void preservesUsernameCaseAndLongPasswords() {
        assertRejected("Student", STUDENT_PASSWORD);
        assertSuccessfulLogin("long-user", LONG_PASSWORD, "Long Password User", "STUDENT");
        assertRejected("long-user", LONG_PASSWORD.substring(0, LONG_PASSWORD.length() - 1));
    }

    @Test
    void rejectsMalformedStoredHashOverHttp() {
        assertRejected("corrupt-hash-user", "TestPassword!");
    }

    @Test
    void queryFailureReturnsTheDocumented401OverHttp() throws Exception {
        try (var connection = DriverManager.getConnection(ConfigProvider.getConfig().getValue("auth.database.url", String.class));
             var statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE legacy_users RENAME TO qa_unavailable_users");
            try {
                assertRejected("student", STUDENT_PASSWORD);
            } finally {
                statement.executeUpdate("ALTER TABLE qa_unavailable_users RENAME TO legacy_users");
            }
        }
        assertSuccessfulLogin("student", STUDENT_PASSWORD, "Workshop Student", "STUDENT");
    }

    @Test
    void preservesNullRequestResponse() {
        try (var response = new AuthResource().login(null)) {
            assertEquals(400, response.getStatus());
            assertEquals(Map.of("message", "Request is null"), response.getEntity());
        }
    }

    @Test
    void jsonNullRequestReturnsTheDocumented400OverHttp() {
        Map<String, Object> body = given().contentType("application/json").accept("application/json").body("null")
                .when().post("/auth/login").then().statusCode(400).contentType("application/json")
                .extract().jsonPath().getMap("");
        assertEquals(Map.of("message", "Request is null"), body);
    }
```

El caso de tabla no disponible es serial: no habilitar ejecucion paralela de estos metodos sobre el mismo fichero. Siempre restaura la tabla en finally. La consulta que falla es intencional; su ERROR de log no indica un fallo de la suite.

### Login: 500 por HTTP

AuthResourceErrorTest usa ErrorProfile. El perfil activa FailingAuthenticationService mediante getEnabledAlternatives; el servicio alternativo no se activa en las pruebas normales ni en produccion:

```java
    public static class ErrorProfile extends AuthResourceTest.IsolatedDatabase implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(FailingAuthenticationService.class);
        }
    }

    @Alternative
    @ApplicationScoped
    public static class FailingAuthenticationService extends AuthenticationService {
        public FailingAuthenticationService() {
            super(null);
        }

        @Override
        public AuthenticatedUser authenticate(String username, String password) {
            throw new IllegalStateException("without-message".equals(username) ? null : "Controlled QA failure");
        }
    }

    @Test
    void unexpectedExceptionReturnsTheDocumented500OverHttp() {
        assertServerError("controlled-failure", "Controlled QA failure");
    }

    @Test
    void nullExceptionMessageReturnsUnknownErrorOverHttp() {
        assertServerError("without-message", "Unknown error");
    }

    private void assertServerError(String username, String expectedMessage) {
        Map<String, Object> body = given().contentType("application/json").accept("application/json")
                .body(Map.of("username", username, "password", "QA-only-fixture"))
                .when().post("/auth/login").then().statusCode(500).contentType("application/json")
                .extract().jsonPath().getMap("");
        assertEquals(Map.of("message", expectedMessage, "exception", IllegalStateException.class.getName()), body);
    }
```

Las caracterizaciones preexistentes del 200 completo, validacion, espacios, escape HTML y SQL siguen en AuthResourceTest y se ejecutaron en la suite final. Las pruebas de soporte no se confundieron con nuevos endpoints: [LegacyDatabaseTest.java](../src/test/java/com/bcp/workshop/auth/LegacyDatabaseTest.java), [PasswordHasherTest.java](../src/test/java/com/bcp/workshop/auth/PasswordHasherTest.java), [UserRepositoryTest.java](../src/test/java/com/bcp/workshop/auth/UserRepositoryTest.java).

## Comandos y resultados realmente ejecutados

Directorio de trabajo: raiz de workshop-auth-backend, `C:/Users/Usuario/Documents/T37716-rodrigo-sebastian-rea-barreto/reqflow-workspace/workshop-auth-backend`. Shell de ejecucion: Git Bash invocado desde PowerShell. JDK: Eclipse Adoptium 21.0.12. No se usaron secretos reales, servicios externos ni la base de desarrollo.

Preparacion usada dentro de cada invocacion de Git Bash:

```bash
cd /c/Users/Usuario/Documents/T37716-rodrigo-sebastian-rea-barreto/reqflow-workspace/workshop-auth-backend
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
```

| Comando ejecutado desde ese directorio | Salida | Resultado real | Evidencia |
|---|---|---|---|
| `mvn -Dtest=HealthResourceTest test` | 0 | 2 tests aprobados, 0 fallos/errores/omitidos | [qa-testing-health.log](../target/qa-testing-health.log) |
| `mvn -Dtest=AuthResourceTest,AuthResourceErrorTest test` | 0 | 16 tests aprobados antes de agregar el caso HTTP null, 0 fallos/errores/omitidos | [qa-testing-login.log](../target/qa-testing-login.log) |
| `mvn -Dtest=AuthResourceTest#jsonNullRequestReturnsTheDocumented400OverHttp test` | 0 | 1 test aprobado, 0 fallos/errores/omitidos | [qa-testing-null-request.log](../target/qa-testing-null-request.log) |
| `mvn -Djacoco.destFile=target/qa-testing-final.exec -Djacoco.dataFile=target/qa-testing-final.exec verify` | 0 | BUILD SUCCESS; 34 tests aprobados; reporte JaCoCo generado | [qa-testing-final.log](../target/qa-testing-final.log) |

En la invocacion final tambien se ejecuta `unset AUTH_DB_URL AUTH_SEED_STUDENT_PASSWORD AUTH_SEED_TRAINER_PASSWORD` para demostrar que los perfiles de pruebas bastan sin configuracion manual de semillas. Los comandos guardaron stdout/stderr en los logs enlazados y propagaron el codigo de salida de Maven. El cierre final fue 2026-10-19T17:02:52-05:00.

Recuento Surefire final:

| Suite | Tests | Fallos | Errores | Omitidos |
|---|---|---|---|---|
| AuthResourceErrorTest | 2 | 0 | 0 | 0 |
| AuthResourceTest | 15 | 0 | 0 | 0 |
| HealthResourceTest | 2 | 0 | 0 | 0 |
| LegacyDatabaseTest | 9 | 0 | 0 | 0 |
| PasswordHasherTest | 4 | 0 | 0 | 0 |
| UserRepositoryTest | 2 | 0 | 0 | 0 |
| Total | 34 | 0 | 0 | 0 |

Los 19 tests de las tres primeras suites son de endpoints, aunque AuthResourceTest conserva dos caracterizaciones por invocacion directa. Los otros 15 tests verifican almacenamiento, hashing y repositorio. Los mensajes ERROR controlados de pruebas negativas no se cuentan como errores JUnit.

## Cobertura y metodo de medicion

[REGLAS.md](../REGLAS.md) exige al menos 85% del codigo modificado. Esta tarea solo modifica codigo de pruebas: JaCoCo reporta clases de produccion, no cobertura de las propias pruebas. Para no confundir esas medidas, se verifico adicionalmente al menos 85% de lineas de cada clase de produccion ejercitada y el total del backend; no se afirma medir cobertura del source de tests ni de clases internas de Quarkus.

JaCoCo 0.8.12 permanecio activo. La ejecucion final uso por primera vez target/qa-testing-final.exec para no sumar ejecuciones historicas; report leyo exactamente ese fichero. Evidencias: [HTML](../target/site/jacoco/index.html), [CSV](../target/site/jacoco/jacoco.csv), [XML](../target/site/jacoco/jacoco.xml). Surefire conserva sus resultados en target/surefire-reports.

Formula: porcentaje de lineas = 100 * LINE_COVERED / (LINE_COVERED + LINE_MISSED). El CSV se leyo con Import-Csv y la comprobacion lanza error si alguna clase queda por debajo del 85%. Esa comprobacion se ejecuto y termino con codigo 0.

| Clase de produccion | Lineas cubiertas / total | Porcentaje |
|---|---|---|
| AuthResource | 17 / 17 | 100% |
| AuthenticationService | 11 / 11 | 100% |
| AuthenticationService.AuthenticatedUser | 1 / 1 | 100% |
| UserRepository | 17 / 17 | 100% |
| LegacyDatabase | 87 / 87 | 100% |
| PasswordHasher | 25 / 27 | 92,59% |
| LoginResponse | 4 / 4 | 100% |
| LoginResponse.User | 1 / 1 | 100% |
| LoginRequest | 1 / 1 | 100% |
| UserCredentials | 1 / 1 | 100% |
| UserRecord | 1 / 1 | 100% |
| LegacyTokenUtil | 3 / 3 | 100% |
| Total | 169 / 171 | 98,83% |

Medicion reproducible desde PowerShell en la misma raiz, correspondiente al procedimiento ejecutado:

```powershell
$rows = Import-Csv target/site/jacoco/jacoco.csv
foreach ($row in $rows) {
    $total = [int]$row.LINE_COVERED + [int]$row.LINE_MISSED
    $percent = if ($total) { 100 * [int]$row.LINE_COVERED / $total } else { 100 }
    if ($percent -lt 85) { throw "Cobertura insuficiente: $($row.CLASS)" }
}
```

No se declaran aprobados CodeQL o Dependency Review: no se ejecutaron localmente ni se modificaron sus workflows. La cobertura es de lineas, no una afirmacion de 85% en todas las ramas.

## Fallos y riesgos conocidos

- En las ejecuciones de esta tarea no hubo fallos de asercion ni errores JUnit. No se relajaron expectativas despues de un fallo para acomodar un bug.
- Los riesgos del token sin firma/secreto recuperable y de detalles internos en 500 siguen documentados en [HALLAZGOS.md](../HALLAZGOS.md). No se descubrio ni corrigio un nuevo bug de produccion en esta ronda.
- Afirmar el 500 documentado es caracterizacion del contrato, no aprobacion de su seguridad. No se agrego una expectativa que exigiera recuperar el secreto del token como comportamiento correcto. Las pruebas de SQL siguen exigiendo rechazo, no el bypass legado.

## Pendientes y pruebas propuestas

Estas filas NO se ejecutaron, NO son tests omitidos por JUnit y NO cuentan en los 34 aprobados:

| endpoint | caso | tipo | resultado esperado |
|---|---|---|---|
| GET /q/health | Salud negativa | Error pendiente | El contrato no fija estado/cuerpo ni un check propio que provoque ese estado. Definirlos primero; despues escribir prueba negativa con fixture controlada, sin agregar un endpoint nuevo. |
| GET /q/health | Excepcion de la extension o Accept no soportado | Error pendiente | Falta especificar codigo, Content-Type y cuerpo. No suponer 406, 500 o 503 ni reutilizar errores de login. |
| POST /auth/login | JSON malformado, tipos incompatibles, negociacion o limites HTTP | Error pendiente | Las respuestas previas al resource son preguntas abiertas en ENDPOINTS.md. Propuesta: caracterizar la dependencia, aprobar expectativas y entonces agregar aserciones; no fijarlas a partir de un fallo arbitrario. |
| POST /auth/login | Peticion HTTP sin cuerpo, distinta de JSON null | Borde pendiente | Su procesamiento por Quarkus sigue abierto en el contrato. La prueba HTTP null ejecutada no demuestra la semantica de ausencia de bytes. |

No hay campos obligatorios ni longitud maxima documentados para GET /q/health: se uso omitir Accept como borde, en lugar de inventar campos vacios o duplicados. No se probaron otros endpoints, consumidores externos, carga, proxies ni despliegues de produccion. La aceptacion completa de errores de salud requiere resolver las preguntas del contrato; el porcentaje de cobertura no elimina esa brecha.

Los logs, SQLite de prueba y reportes bajo target son artefactos locales regenerables; `mvn clean` elimina los logs de esta ronda. No se debe ejecutar clean y luego presentar estos enlaces como resultados nuevos sin repetir los comandos.

## Documentos consultados

- [ENDPOINTS.md](ENDPOINTS.md): endpoints permitidos, campos, respuestas concretas y preguntas abiertas.
- [REGLAS.md](../REGLAS.md): meta de cobertura, pruebas previas, Clean Code y prohibicion de silenciar gates.
- [pom.xml](../pom.xml): JUnit/Quarkus, RestAssured y JaCoCo usados por la suite.
- [AuthResourceTest.java](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java) y las demas suites enlazadas: inventario de expectativas existentes y codigo ejecutado.
- [application.properties](../src/main/resources/application.properties): configuracion de laboratorio, SQLite y ausencia de autenticacion HTTP declarada. El perfil de pruebas sustituye solo datos y semillas, no el contrato de produccion.