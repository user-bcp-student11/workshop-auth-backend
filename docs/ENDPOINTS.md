# Contrato actual de endpoints

Documentacion del codigo actual, no una propuesta de cambios. Base local por defecto: `http://127.0.0.1:8080`. El perfil prod declara host `0.0.0.0`; una URL publica o TLS de despliegue no estan definidos en el repositorio. Evidencia: [application.properties:1](../src/main/resources/application.properties#L1).

Los ejemplos de login corresponden a ramas del codigo o fixtures de las pruebas; no son capturas de una nueva ejecucion HTTP. No se garantiza el orden de las propiedades JSON. Los detalles no verificables se marcan como preguntas abiertas.

## POST /auth/login

### 1. Que hace

Autentica una cuenta de laboratorio y entrega su identidad, rol y token para continuar el flujo de la aplicacion.

Busca username mediante SQL parametrizado y verifica la contrasena contra su hash; ya no compara contrasenas legibles en SQL. Evidencia: [AuthenticationService.java:21](../src/main/java/com/bcp/workshop/auth/AuthenticationService.java#L21), [UserRepository.java:18](../src/main/java/com/bcp/workshop/auth/UserRepository.java#L18).

### 2. Autenticacion y headers

No requiere autenticacion previa ni header `Authorization`. Las credenciales se reciben en el cuerpo. No se declara autenticacion Basic ni Bearer para este endpoint.

Headers de la solicitud de integracion:

```http
Content-Type: application/json
Accept: application/json
```

Content-Type identifica el cuerpo JSON. Accept solicita la representacion disponible; no es un header de autenticacion. Evidencia: [AuthResource.java:12](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L12), [AuthResource.java:19](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L19). No hay anotacion de seguridad ni lectura de Authorization en ese resource.

### 3. Request

| campo | tipo | obligatorio | descripcion |
|---|---|---|---|
| username | string JSON | Si | Nombre de cuenta. Ausencia, null, cadena vacia o en blanco se rechazan. Se busca la cadena original, sin recortar espacios ni normalizar mayusculas. |
| password | string JSON | Si | Contrasena original, no su hash. Ausencia, null, cadena vacia o en blanco se rechazan. No se recorta antes de verificarla. |
| client | string JSON o null | No | Identificador admitido por el DTO, pero ignorado en la autenticacion. Puede omitirse. |

Ejemplo completo, con todos los campos declarados:

```json
{
  "username": "student",
  "password": "Workshop123!",
  "client": "angular-workshop"
}
```

La validacion sigue el orden cuerpo, username, password. `trim().isEmpty()` solo detecta campos en blanco; no cambia los valores usados al autenticar. Las propiedades JSON desconocidas se ignoran. El resource no fija longitudes maximas. La conversion de tipos JSON distintos de string no esta caracterizada y no debe usarse como contrato.

Evidencia: [LoginRequest.java:3](../src/main/java/com/bcp/workshop/auth/LoginRequest.java#L3), [AuthResource.java:23](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L23), [application.properties:8](../src/main/resources/application.properties#L8), [AuthResourceTest.java:90](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java#L90).

**Cuenta del ejemplo:** en una base nueva, student solo se crea si AUTH_SEED_STUDENT_PASSWORD tiene un valor no blanco; para reproducir el ejemplo local usar el valor de laboratorio Workshop123!. trainer necesita AUTH_SEED_TRAINER_PASSWORD; su valor de laboratorio documentado es Trainer123!. No son campos de la API ni valores por defecto en Java. Las semillas no reemplazan cuentas existentes. Nunca usar estos valores como secretos de produccion.

AUTH_DB_URL selecciona SQLite; su defecto es `jdbc:sqlite:./data/workshop.db`. La inicializacion migra registros legados a hashes y conserva identidad y rol. Evidencia: [application.properties:9](../src/main/resources/application.properties#L9), [LegacyDatabase.java:76](../src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L76), [LegacyDatabase.java:140](../src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L140), [README.md:17](../README.md#L17).

### 4. Response exitosa

**HTTP 200 OK**, `Content-Type: application/json`.

| campo | tipo | descripcion |
|---|---|---|
| token | string JSON | Token legado Base64 del usuario autenticado. No es JWT ni esta firmado. Depende del instante de emision. |
| expiresIn | integer JSON | Valor fijo actual: 3600 segundos. Duracion anunciada, no evidencia de rechazo efectivo por un consumidor tras ese plazo. |
| user | object JSON | Identidad publica; no incluye id, password ni password_hash. |
| user.username | string JSON | Username almacenado de la cuenta autenticada. |
| user.displayName | string JSON | Nombre almacenado transformado con escape HTML4. `<b>QA & Test</b>` se devuelve como `&lt;b&gt;QA &amp; Test&lt;/b&gt;`. |
| user.role | string JSON | Rol almacenado. Las semillas usan STUDENT y TRAINER; no hay enum que limite otros valores existentes. |
| debug | string JSON | Valor fijo actual: `authenticated-with-legacy-flow`; sigue presente tras el refactor. |

Ejemplo completo para la cuenta student de laboratorio:

```json
{
  "token": "c3R1ZGVudHxTVFVERU5UfDE3OTI0NDgxMzJ8V09SS1NIT1BfU0VDUkVUXzIwMjY=",
  "expiresIn": 3600,
  "user": {
    "username": "student",
    "displayName": "Workshop Student",
    "role": "STUDENT"
  },
  "debug": "authenticated-with-legacy-flow"
}
```

Token calculado con el algoritmo real para username student, role STUDENT y expiry 1792448132 (epoch en segundos). Es un ejemplo determinista, no un token recien emitido ni reutilizable. Su contenido decodificado sigue `username|role|expiry|SECRET`. El emisor calcula expiry como el instante de emision mas 3600 segundos.

Evidencia de campos y token: [LoginResponse.java:5](../src/main/java/com/bcp/workshop/auth/LoginResponse.java#L5), [LoginResponse.java:9](../src/main/java/com/bcp/workshop/auth/LoginResponse.java#L9), [LegacyTokenUtil.java:12](../src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L12). Las propiedades exactas y las semillas se comprueban en [AuthResourceTest.java:74](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java#L74) y [AuthResourceTest.java:156](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java#L156).

### 5. Respuestas de error

Estas ramas del metodo login devuelven JSON. En 400/401 el cuerpo contiene solo message; en 500 contiene message y exception. No devuelven token ni user.

**HTTP 400 Bad Request: cuerpo null.** Si el metodo recibe request null:

```json
{
  "message": "Request is null"
}
```

Esta rama esta probada por invocacion directa: no se ha confirmado que toda solicitud HTTP sin cuerpo llegue al metodo en lugar de ser rechazada antes por Quarkus. Evidencia: [AuthResource.java:23](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L23), [AuthResourceTest.java:114](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java#L114).

**HTTP 400 Bad Request: username ausente, null, vacio o en blanco.** Tiene precedencia aunque password tambien sea invalido:

```json
{
  "message": "Username is required"
}
```

**HTTP 400 Bad Request: password ausente, null, vacio o en blanco**, con username valido:

```json
{
  "message": "Password is required"
}
```

Evidencia: [AuthResource.java:26](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L26), [AuthResource.java:29](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L29), [AuthResourceTest.java:90](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java#L90).

**HTTP 401 Unauthorized: credenciales no verificadas.** Usuario inexistente, password incorrecto, hash invalido o consulta/almacenamiento no disponible capturado por el repositorio. Un fallo de inicializacion/migracion deja el almacenamiento no disponible y sus consultas tambien terminan en esta rama. No hay un codigo especifico de indisponibilidad de SQLite.

Ejemplo para username student y password incorrecto:

```json
{
  "message": "Invalid user student or password"
}
```

El mensaje incorpora exactamente el username recibido, incluidos sus espacios. Esta rama no configura WWW-Authenticate. Evidencia: [AuthResource.java:32](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L32), [AuthenticationService.java:21](../src/main/java/com/bcp/workshop/auth/AuthenticationService.java#L21), [UserRepository.java:30](../src/main/java/com/bcp/workshop/auth/UserRepository.java#L30), [LegacyDatabase.java:65](../src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L65), [PasswordHasher.java:33](../src/main/java/com/bcp/workshop/auth/PasswordHasher.java#L33).

**HTTP 500 Internal Server Error: excepcion que llega al catch del resource.** Puede surgir en el servicio, emision del token o mapeo de respuesta. message es el mensaje Java real y exception el nombre completo de clase; sus valores no forman una lista cerrada.

Ejemplo completo de la fixture controlada de prueba, no de un fallo espontaneo observado:

```json
{
  "message": "Controlled QA failure",
  "exception": "java.lang.IllegalStateException"
}
```

Si el mensaje Java es null, esa fixture devuelve:

```json
{
  "message": "Unknown error",
  "exception": "java.lang.IllegalStateException"
}
```

Evidencia: [AuthResource.java:38](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L38), [AuthResourceTest.java:122](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java#L122).

**Preguntas abiertas: errores previos al metodo.** No hay ExceptionMapper propio que fije los cuerpos para JSON malformado, conversion de tipos, negociacion de Content-Type/Accept o limites HTTP. Esos rechazos los gestiona Quarkus REST: falta verificar estado, Content-Type y cuerpo de cada caso en la dependencia resuelta antes de publicarlos como contrato. No se inventan ejemplos ni se presupone el campo message. El cliente debe comprobar estado/Content-Type y tolerar errores no JSON de framework o infraestructura.

### 6. Notas de seguridad

- Username se consulta con PreparedStatement; password se verifica con PBKDF2WithHmacSHA256, 600000 iteraciones, salt aleatorio de 16 bytes y hash de 32 bytes. El cliente envia la contrasena, no un hash. Evidencia: [UserRepository.java:20](../src/main/java/com/bcp/workshop/auth/UserRepository.java#L20), [PasswordHasher.java:15](../src/main/java/com/bcp/workshop/auth/PasswordHasher.java#L15).
- **El token sigue sin firma y revela el secreto de laboratorio al decodificarlo.** Contiene expiry, pero este backend no tiene un endpoint que verifique token o expiracion. No se puede afirmar que "expira de verdad" en los consumidores. Su compatibilidad y validacion siguen abiertas; no inferir Authorization Bearer para otros servicios. Evidencia: [LegacyTokenUtil.java:8](../src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L8), [LegacyTokenUtil.java:13](../src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L13).
- El 500 conserva detalles internos y el 401 refleja username. No registrar passwords/tokens ni insertar mensajes sin escape en HTML. El escape HTML4 de displayName es parte del valor de salida, no permiso para usar innerHTML.
- CORS admite cualquier origen mediante `/.*/`; no es control de acceso ni garantia de envio de cookies. HTTP local no cifra credenciales. Este laboratorio debe permanecer aislado y no exponerse en Internet. Evidencia: [application.properties:4](../src/main/resources/application.properties#L4), [README.md:3](../README.md#L3).

## GET /q/health

### 1. Que hace

Expone salud de la instancia para que quien opera la aplicacion pueda consultar su disponibilidad.

La ruta esta publicada en [README.md:25](../README.md#L25) y la aporta quarkus-smallrye-health, declarada en [pom.xml:40](../pom.xml#L40), con BOM Quarkus 3.27.4 en [pom.xml:13](../pom.xml#L13). No hay resource de salud ni implementaciones propias de HealthCheck, Readiness o Liveness en src. No confundir salud de la instancia con una prueba de login o de SQLite.

### 2. Autenticacion y headers

El repositorio no configura autenticacion de salud ni un header de credenciales. No se requiere Authorization en la configuracion local declarada; un proxy puede imponer requisitos adicionales no definidos aqui.

Solicitud recomendada sin autenticacion, negociando JSON:

```http
GET /q/health HTTP/1.1
Host: 127.0.0.1:8080
Accept: application/json
```

[application.properties](../src/main/resources/application.properties) no declara permisos HTTP ni puerto de gestion separado. Pregunta abierta: confirmar headers/politica efectivos de la extension y del entorno de destino.

### 3. Request

| campo | tipo | obligatorio | descripcion |
|---|---|---|---|
| (ninguno definido por la aplicacion) | No aplica | No | No hay parametros de path/query ni DTO/cuerpo JSON definidos por el codigo propio para esta ruta. |

**Ejemplo JSON de request: no aplica.** La solicitud HTTP completa del punto 2 no lleva cuerpo. Enviar `{}` o `null` como payload requerido inventaria un contrato que no existe en el codigo propio.

Pregunta abierta: la tolerancia de la extension a cuerpos GET o parametros adicionales no esta caracterizada; integrar sin ellos.

### 4. Response exitosa

**Codigo y cuerpo pendientes de verificacion de la extension.** No hay respuesta propia ni prueba de salud que permita fijarlos leyendo el codigo del repositorio. Declarar la dependencia no demuestra los valores concretos devueltos.

| campo | tipo | descripcion |
|---|---|---|
| (sin campos propios verificables) | Pendiente | La extension construye la representacion; falta confirmar su esquema efectivo. |

**Ejemplo JSON completo: pregunta abierta.** Falta capturar la respuesta exitosa de GET /q/health o inspeccionar la implementacion de la dependencia efectivamente resuelta. No se presentan status, checks ni valores concretos como evidencia confirmada por una suposicion sobre MicroProfile Health. Esta limitacion impide cerrar la integracion de salud sin verificacion adicional.

### 5. Respuestas de error

El codigo propio no define ramas de error, codigos ni cuerpos para esta ruta. No reutilizar las respuestas de AuthResource.

| codigo | cuando ocurre | ejemplo de cuerpo |
|---|---|---|
| Pendiente | Salud negativa gestionada por la extension, si es alcanzable con esta configuracion. | Pregunta abierta: obtener una respuesta negativa real o verificar el serializador y mapeo HTTP de la dependencia; no hay JSON propio que permita reproducirlo. |
| Pendiente | Excepcion de la extension o negociacion de Accept no soportada. | Pregunta abierta: confirmar estado, Content-Type y cuerpo; no se inventa JSON de error. |

Si el proceso no escucha o la conexion falla, no existe respuesta HTTP del backend: es un error de transporte, no un supuesto cuerpo JSON.

### 6. Notas de seguridad

- No hay comprobacion propia de SQLite o credenciales asociada a salud. No usar esta consulta como garantia de que un usuario podra autenticarse.
- El fallo de inicializacion de SQLite se captura sin propagarse; la extension no sustituye la observacion de ese fallo. Evidencia: [LegacyDatabase.java:49](../src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L49).
- Red y CORS siguen [application.properties](../src/main/resources/application.properties). La exposicion externa de salud debe controlarse en el entorno; no se afirma que exista un control desplegado.

## Documentos y codigo consultados

- [README.md](../README.md): ejecucion, advertencia de laboratorio, credenciales y rutas publicadas. Las credenciales requieren ahora la configuracion de semillas indicada arriba.
- Version previa de este documento: contrato legacy a contrastar, no autoridad sobre mecanismos ya corregidos.
- [pom.xml](../pom.xml) y [src/main/resources/application.properties](../src/main/resources/application.properties): Quarkus, extensiones, escucha, CORS, JSON, semillas y SQLite.
- [AuthResource.java](../src/main/java/com/bcp/workshop/auth/AuthResource.java), [LoginRequest.java](../src/main/java/com/bcp/workshop/auth/LoginRequest.java) y [LoginResponse.java](../src/main/java/com/bcp/workshop/auth/LoginResponse.java): ruta, tipos, validaciones y respuestas.
- [AuthenticationService.java](../src/main/java/com/bcp/workshop/auth/AuthenticationService.java), [UserRepository.java](../src/main/java/com/bcp/workshop/auth/UserRepository.java), [LegacyDatabase.java](../src/main/java/com/bcp/workshop/auth/LegacyDatabase.java) y [PasswordHasher.java](../src/main/java/com/bcp/workshop/auth/PasswordHasher.java): autenticacion, almacenamiento, migracion y hashing.
- [LegacyTokenUtil.java](../src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java): formato real y limites del token.
- [AuthResourceTest.java](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java): fixtures y expectativas de exito, validacion, credenciales y excepciones.

Solo se modifica documentacion. Se omitieron el arranque temporal y la inspeccion local de dependencias; la consulta externa tampoco produjo contenido verificable. Salud y errores de framework permanecen como preguntas abiertas, no como comprobaciones aprobadas.