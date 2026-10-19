# Contrato actual de endpoints

Reconstruido a partir del codigo porque este documento no existia. Describe el comportamiento actual que se debe preservar, sin proponer cambios del contrato. El seguimiento se mantiene exclusivamente en [HALLAZGOS.md](../HALLAZGOS.md).

## POST /auth/login

Consume y produce `application/json`. Declaraciones: [AuthResource.java:15](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L15), [AuthResource.java:16](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L16), [AuthResource.java:17](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L17), [AuthResource.java:22](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L22) y [AuthResource.java:23](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L23).

### Solicitud

```json
{
  "username": "student",
  "password": "Workshop123!",
  "client": "angular-workshop"
}
```

- username y password son strings obligatorios para login. Se rechazan null, vacios o en blanco segun `trim().isEmpty()`.
- `trim()` solo valida: las credenciales originales se envian a la consulta, sin recortarlas.
- client es un string admitido por el DTO, no obligatorio y sin uso en este flujo.
- Se ignoran propiedades JSON desconocidas por configuracion.

Evidencia: [LoginRequest.java:3](../src/main/java/com/bcp/workshop/auth/LoginRequest.java#L3), [AuthResource.java:26](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L26), [AuthResource.java:29](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L29), [AuthResource.java:32](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L32), [AuthResource.java:35](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L35) y [application.properties:8](../src/main/resources/application.properties#L8).

### Respuestas

| HTTP | Condicion dentro del metodo | Cuerpo JSON |
|---|---|---|
| 200 | Se encuentra usuario | `{"token":"<string>","expiresIn":3600,"user":{"username":"<string>","displayName":"<string con escape HTML4>","role":"<string>"},"debug":"authenticated-with-legacy-flow"}` |
| 400 | request null | `{"message":"Request is null"}` |
| 400 | username null o en blanco | `{"message":"Username is required"}` |
| 400 | password null o en blanco, con username valido | `{"message":"Password is required"}` |
| 401 | La consulta retorna null | `{"message":"Invalid user <username recibido> or password"}` |
| 500 | Una excepcion alcanza el catch del resource | `{"message":"<mensaje de excepcion, o Unknown error si es null>","exception":"<nombre completo de clase de excepcion>"}` |

La validacion sigue el orden request, username, password. No se garantiza el orden de las propiedades JSON. Un fallo SQL capturado en LegacyDatabase tambien retorna null y llega al 401. Errores de transporte, JSON malformado y conversion de tipos ocurren fuera del metodo; no se han caracterizado sus cuerpos de respuesta.

Evidencia: [AuthResource.java:27](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L27), [AuthResource.java:30](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L30), [AuthResource.java:33](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L33), [AuthResource.java:37](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L37), [AuthResource.java:40](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L40), [AuthResource.java:54](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L54) y [LegacyDatabase.java:39](../src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L39).

### Flujo completo

1. Al crear la instancia de LegacyDatabase, `@PostConstruct` crea el directorio relativo data, la tabla SQLite legacy_users si falta y dos usuarios mediante `INSERT OR IGNORE`: student / Workshop123! y trainer / Trainer123!. No reemplaza registros existentes. Los errores se imprimen y no se propagan. [LegacyDatabase.java:17](../src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L17).
2. AuthResource recibe LoginRequest y realiza las validaciones anteriores. Consulta con username y password originales. [AuthResource.java:24](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L24).
3. `findByCredentials` concatena las credenciales en el SQL, abre una conexion por consulta y ejecuta un Statement. Si existe una fila, toma la primera y devuelve UserRecord con id, username, displayName y role; si no hay filas o captura una excepcion, devuelve null. Los recursos JDBC se cierran con try-with-resources. [LegacyDatabase.java:31](../src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L31), [UserRecord.java:3](../src/main/java/com/bcp/workshop/auth/UserRecord.java#L3).
4. Sin usuario, el resource responde 401. Con usuario, `LegacyTokenUtil.create` calcula expiry como epoch en segundos del instante actual mas 3600 segundos y codifica en Base64 los bytes UTF-8 de `username|role|expiry|SECRET`. No hay firma ni validacion del token en esta clase. No se inspeccionaron consumidores externos. [AuthResource.java:36](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L36), [LegacyTokenUtil.java:12](../src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L12).
5. El resource arma la respuesta 200 con token, expiresIn, user y debug. Solo displayName recibe escape HTML4. El catch devuelve 500 con mensaje y clase de la excepcion. [AuthResource.java:40](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L40), [AuthResource.java:54](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L54).

### Configuracion

| Ajuste | Valor declarado | Evidencia |
|---|---|---|
| Base de datos | AUTH_DB_URL del entorno; defecto `jdbc:sqlite:./data/workshop.db`, evaluado al cargar la clase | [LegacyDatabase.java:15](../src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L15) |
| Secreto | Literal de laboratorio WORKSHOP_SECRET_2026 en Java | [LegacyTokenUtil.java:8](../src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L8) |
| Duracion | 3600 segundos en el token y expiresIn 3600 en la respuesta | [LegacyTokenUtil.java:13](../src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L13), [AuthResource.java:42](../src/main/java/com/bcp/workshop/auth/AuthResource.java#L42) |
| HTTP | Puerto 8080, host 127.0.0.1; perfil prod: host 0.0.0.0 | [application.properties:1](../src/main/resources/application.properties#L1), [application.properties:2](../src/main/resources/application.properties#L2), [application.properties:3](../src/main/resources/application.properties#L3) |
| CORS | Habilitado; origen `/.*/`; metodos GET,POST,PUT,DELETE,OPTIONS; cabeceras accept,authorization,content-type,x-requested-with | [application.properties:4](../src/main/resources/application.properties#L4), [application.properties:5](../src/main/resources/application.properties#L5), [application.properties:6](../src/main/resources/application.properties#L6), [application.properties:7](../src/main/resources/application.properties#L7) |
| JSON | Se ignoran propiedades desconocidas | [application.properties:8](../src/main/resources/application.properties#L8) |
| Dependencias del flujo | BOM Quarkus 3.27.4, SQLite JDBC 3.50.3.0 y Commons Text 1.9 | [pom.xml:13](../pom.xml#L13), [pom.xml:45](../pom.xml#L45), [pom.xml:50](../pom.xml#L50) |

Son valores del repositorio, no una comprobacion de la configuracion efectiva de despliegue.

### Prueba existente

[AuthResourceTest.java:13](../src/test/java/com/bcp/workshop/auth/AuthResourceTest.java#L13) envia las credenciales de student con client y comprueba HTTP 200, user.username y token no null. No caracteriza todas las propiedades ni las respuestas de error. Este documento se reconstruye por inspeccion, no representa una nueva ejecucion de pruebas.

## GET /q/health

Anunciado en [README.md:25](../README.md#L25) y provisto por la extension quarkus-smallrye-health declarada en [pom.xml:40](../pom.xml#L40). No se observa un handler propio ni una prueba de salud en los archivos revisados. El estado y el cuerpo los gestiona la extension; no se presentan como una respuesta observada en esta revision.