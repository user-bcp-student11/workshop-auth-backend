# Plan de correccion del login

## Alcance y restricciones

Propuesta para H-01, H-03 y H-02 de [HALLAZGOS.md](HALLAZGOS.md), actualmente Pendiente, en el orden solicitado. No es una implementacion ni un registro paralelo de estados: el seguimiento permanece exclusivamente en esa matriz.

Solo se trabaja en workshop-auth-backend. En esta entrega se crea unicamente PLAN.md; no se modifican codigo, pruebas, dependencias, configuracion ni [docs/ENDPOINTS.md](docs/ENDPOINTS.md). Los archivos nuevos mencionados abajo son propuestas, no archivos creados.

Las convenciones proceden de [docs/GOOD_PRACTICES.md](docs/GOOD_PRACTICES.md): Resource -> Service -> Repository, DTO de salida propio, hash lento con salt, tokens firmados con expiracion y claims, configuracion externa y SQL parametrizado. Las restricciones de Clean Code y cobertura proceden de [REGLAS.md](REGLAS.md).

## Contrato y decisiones previas

- Preservar POST /auth/login, application/json, username/password sin recorte antes de autenticar, client opcional e ignorado y tolerancia a propiedades desconocidas.
- Preservar la forma del 200: token string, expiresIn 3600, user con username/displayName/role, escape HTML4 de displayName y debug con su valor actual. No devolver UserRecord ni incluir password o hash en el DTO HTTP.
- Preservar orden de validacion, codigos y mensajes 400/401 documentados. No normalizar mayusculas ni espacios de las credenciales.
- El contrato describe que los fallos de consulta retornan null y producen 401. Mantener ese comportamiento durante estos cambios; no convertirlo incidentalmente en 500.
- Existe una tension entre el 500 documentado, que expone mensaje/clase, y las buenas practicas de errores genericos. H-05 no esta en este alcance: conservar el comportamiento caracterizado, documentar la deuda y no aprovechar este trabajo para cambiarlo. Cualquier nueva exposicion de credenciales en errores o logs queda prohibida.
- SQL concatenado, almacenamiento legible y algoritmo del token aparecen en el apartado de flujo interno del contrato. Se propone corregir esos mecanismos, no reescribir el documento ni las respuestas HTTP. En particular, un JWT firmado cambia necesariamente el valor/formato del token legado y puede afectar consumidores que lo decodifican.
- **Condicion de H-03:** confirmar con el responsable del contrato que el token se consume como string opaco y coordinar soporte del formato firmado en los consumidores, sin leerlos ni modificarlos aqui. Si la codificacion Base64 legada es obligatoria, H-03 no puede corregirse completamente bajo esa restriccion: detener su implementacion y solicitar decision explicita. No afirmar compatibilidad, introducir dos formatos ni mantener un fallback inseguro.

## H-01 - SQL concatenado

### Hallazgo y problema

H-01 de la matriz, severidad alta, Pendiente. [LegacyDatabase.java:32](src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L32) concatena username/password y [LegacyDatabase.java:35](src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L35) ejecuta la cadena con Statement. La entrada puede modificar la consulta y permitir autenticacion indebida.

### Caracterizacion antes de tocar persistencia

Ampliar [src/test/java/com/bcp/workshop/auth/AuthResourceTest.java](src/test/java/com/bcp/workshop/auth/AuthResourceTest.java) antes de cambiar produccion:

- Login valido de student y trainer: comprobar 200, Content-Type, todos los campos y tipos del cuerpo, expiresIn, debug, nombres/roles y ausencia de credenciales en la respuesta. Caracterizar escape HTML4 con un usuario de prueba creado de forma parametrizada.
- Usuario inexistente, password incorrecto, credenciales con espacios exteriores: comprobar 401 y mensaje exacto con username original; las entradas en blanco deben seguir la validacion 400.
- Caracterizar request null mediante invocacion directa si el transporte no llega a ese caso; comprobar por HTTP username/password ausentes, null, vacios y en blanco, precedencia de validacion, client omitido y propiedades desconocidas.
- Caracterizar el retorno null ante fallo de consulta usando una SQLite temporal sin tabla en una ejecucion aislada; comprobar que el resource lo traduce al 401 documentado. No corromper ni renombrar la base de desarrollo.

Usar SQLite temporal bajo target, AUTH_DB_URL definido antes de cargar LegacyDatabase, datos aislados y limpieza al terminar. La base fija de desarrollo no debe ser requisito. Un caso que documente el bypass actual es evidencia temporal del defecto, no una expectativa permanente que deba conservarse.

### Cambio propuesto

1. Sustituir la consulta por SQL constante con `WHERE username = ? AND password = ?`, PreparedStatement y setString para ambos valores. Conservar try-with-resources y mapeo explicito de la fila.
2. Separar la consulta en un repositorio; LegacyDatabase queda limitado a inicializacion y migracion del almacenamiento. Extraer un servicio de autenticacion que coordine el repositorio y la emision del token. El resource conserva solo validacion HTTP, delegacion y mapeo de la respuesta a DTO.
3. Mantener la semantica de error de consulta caracterizada sin registrar SQL con parametros ni contrasenas. Parametrizar tambien las inserciones con valores variables. El DDL fijo puede seguir usando Statement porque no recibe entrada.
4. Agregar regresiones que deben pasar tras corregir: comillas, delimitadores y expresiones SQL en cualquiera de las credenciales se tratan como valores literales y no autentican a otro usuario; usuarios con caracteres especiales validos pueden autenticar con coincidencia exacta.

### Archivos previstos

- Modificar [src/main/java/com/bcp/workshop/auth/LegacyDatabase.java](src/main/java/com/bcp/workshop/auth/LegacyDatabase.java), [src/main/java/com/bcp/workshop/auth/AuthResource.java](src/main/java/com/bcp/workshop/auth/AuthResource.java) y [src/test/java/com/bcp/workshop/auth/AuthResourceTest.java](src/test/java/com/bcp/workshop/auth/AuthResourceTest.java).
- Nuevos bajo src/main/java/com/bcp/workshop/auth/: UserRepository.java, AuthenticationService.java y LoginResponse.java, con DTO propio para user.
- Nuevo bajo src/test/java/com/bcp/workshop/auth/: UserRepositoryTest.java, reutilizado por H-02. Conservar [src/main/java/com/bcp/workshop/auth/LoginRequest.java](src/main/java/com/bcp/workshop/auth/LoginRequest.java) y [src/main/java/com/bcp/workshop/auth/UserRecord.java](src/main/java/com/bcp/workshop/auth/UserRecord.java) salvo necesidad interna demostrada.

No requiere dependencias nuevas: JDBC ya ofrece PreparedStatement.

## H-03 - Token sin firma y secreto expuesto

### Hallazgo y problema

H-03 de la matriz, severidad alta, Pendiente. [LegacyTokenUtil.java:8](src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L8) fija el secreto y [LegacyTokenUtil.java:14](src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L14) lo incluye en el contenido Base64 sin firma. La fecha de expiracion si existe; no hay evidencia aqui de como la validan consumidores externos.

### Caracterizacion antes de tocar el token

- En la prueba HTTP existente, comprobar que token es un string no vacio, expiresIn es 3600 y username/role pertenecen al usuario autenticado; conservar el resto del JSON sin comparar el token completo, pues depende del tiempo.
- Crear LegacyTokenUtilTest.java para documentar, solo antes de sustituirlo, que el formato actual contiene usuario, rol y expiry dentro de la ventana del inicio/fin de la llamada mas 3600 segundos. Confirmar el defecto del secreto recuperable sin imprimir el token ni el secreto. Esa expectativa insegura se retira al sustituir el emisor, conservando el resultado previo como evidencia, no como compatibilidad exigida.
- Caracterizar el 500 del resource con una dependencia de prueba que lance una excepcion de mensaje inocuo y otra sin mensaje; comprobar Unknown error y el campo exception segun el contrato. No agregar una ruta HTTP para forzar errores.

### Cambio propuesto, sujeto a la condicion de compatibilidad

1. Usar un emisor JWT de biblioteca probada, con firma RS256 y claims explicitos sub, role, iat, exp e iss. Mantener role, no transformarlo silenciosamente en otro esquema de autorizacion. No construir JWT ni criptografia manualmente.
2. Externalizar ubicacion de clave privada y emisor mediante propiedades respaldadas por variables de entorno, por ejemplo AUTH_TOKEN_SIGN_KEY_LOCATION y AUTH_TOKEN_ISSUER. No introducir secretos por defecto, claves reales en Git ni claves en el payload. Los consumidores necesitan solo la clave publica.
3. Definir una unica constante de duracion de 3600 segundos, compartida por exp y expiresIn. Inyectar Clock para verificar limites temporales de forma determinista. Separar carga de configuracion y emision del token.
4. Sustituir el uso de LegacyTokenUtil por un TokenService inyectable desde AuthenticationService. Retirar el emisor legado cuando no tenga referencias; no mantenerlo como alternativa insegura. La respuesta HTTP mantiene su estructura, incluido debug.
5. Agregar pruebas del nuevo emisor con par de claves exclusivo de pruebas: firma verificable con clave publica correcta, rechazo con otra clave y ante payload/firma alterados, sub/role/iss correctos, exp = iat + 3600, y casos justo antes/en/despues de exp con reloj controlado. Las pruebas pueden usar el verificador JOSE de la biblioteca elegida; no basta comprobar tres segmentos del JWT. Verificar que configuracion ausente/invalida impide emitir tokens sin divulgar claves.
6. No agregar endpoints protegidos ni un servicio verificador sin consumidor local. La validacion real de expiracion y firma en sistemas externos exige evidencia coordinada de sus responsables; no dar H-03 por cerrado basandose solo en las pruebas del emisor.

### Archivos previstos y dependencia justificada

- Modificar [src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java](src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java) para su retirada, [src/main/java/com/bcp/workshop/auth/AuthResource.java](src/main/java/com/bcp/workshop/auth/AuthResource.java), AuthenticationService.java propuesto en H-01 y [src/main/resources/application.properties](src/main/resources/application.properties).
- Agregar TokenService.java bajo src/main/java/com/bcp/workshop/auth/ y TokenServiceTest.java bajo src/test/java/com/bcp/workshop/auth/. La caracterizacion temporal LegacyTokenUtilTest.java se sustituye por estas pruebas al retirar la clase.
- Modificar [pom.xml](pom.xml) solo para incorporar io.quarkus:quarkus-smallrye-jwt-build, con version gestionada por el BOM existente: justificada porque aporta emision JWT y firma JOSE probadas. Confirmar la API y dependencia de verificacion disponible; si requiere dependencia de prueba explicita, justificar sus coordenadas antes de incorporarla. No agregar la extension de autenticacion bearer sin necesidad local ni actualizar el BOM como parte de este hallazgo.
- Usar claves de prueba generadas en memoria o recursos exclusivos de src/test/resources, nunca configuracion de produccion. La configuracion de CORS queda intacta: H-04 no se cierra por externalizar la clave de H-03.

## H-02 - Contrasenas en texto plano

### Hallazgo y problema

H-02 de la matriz, severidad alta, Pendiente. [LegacyDatabase.java:23](src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L23) y [LegacyDatabase.java:24](src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L24) insertan claves legibles; la consulta en [LegacyDatabase.java:32](src/main/java/com/bcp/workshop/auth/LegacyDatabase.java#L32) compara la contrasena directamente. Una copia del almacenamiento expone credenciales reutilizables.

### Caracterizacion antes de tocar almacenamiento

- Reutilizar los casos de login valido/invalido de H-01 y UserRepositoryTest.java. Preparar una SQLite temporal con el esquema legado y usuarios personalizados, ademas de student/trainer.
- Antes del cambio, caracterizar que reiniciar no reemplaza usuarios existentes por las semillas, que se conservan id/username/displayName/role y que las credenciales originales siguen funcionando con coincidencia exacta.
- Caracterizar primera inicializacion y reinicializacion. Registrar el esquema original como fixture aislada, no copiar datos de desarrollo ni imprimir passwords en evidencias. La expectativa de almacenamiento legible no se conserva tras corregir.

### Cambio propuesto

1. Implementar PasswordHasher con PBKDF2WithHmacSHA256 de JDK 21, SecureRandom, salt unico y comparacion constante mediante MessageDigest.isEqual. PBKDF2 cumple hash lento con salt; BCrypt es un ejemplo, no una obligacion en GOOD_PRACTICES.md. Guardar algoritmo/version, iteraciones, salt y hash en un formato estructurado validable; no inventar primitivas criptograficas.
2. Proponer 600000 iteraciones, salt de 16 bytes y derivacion de 32 bytes como parametros iniciales con constantes de nombre explicito, sujetos a medir latencia/capacidad antes de adoptar. Acotar parametros y longitud de los valores almacenados al parsear para evitar trabajo descontrolado. No truncar ni recortar passwords, ni introducir un limite HTTP nuevo sin autorizacion contractual.
3. Usar una migracion SQLite transaccional y versionada. Leer el esquema/version, crear tabla de reemplazo con password_hash y sin password, derivar hashes de las filas legadas, preservar ids y demas columnas y sustituir la tabla dentro de la transaccion. No detectar filas migradas solo por un prefijo que podria ser una contrasena legitima. Repetir la inicializacion no debe volver a hashear ni sobrescribir registros.
4. Ante fallo de migracion, rollback y no habilitar autenticacion por texto plano como fallback; emitir solo informacion operativa sin valores sensibles. Definir antes de ejecutar el procedimiento de respaldo restringido y recuperacion: un respaldo legado tambien contiene passwords y debe tener retencion limitada. Evaluar y verificar limpieza de paginas libres y WAL conforme a las capacidades de SQLite, fuera de la transaccion cuando corresponda; borrar la columna no garantiza borrado fisico ni elimina copias externas.
5. Crear las semillas solo cuando falten usuarios, almacenando desde el principio sus hashes. Obtener las credenciales de inicializacion de configuracion externa o fixtures de pruebas; no persistirlas ni registrarlas en claro. Mantener las credenciales de laboratorio documentadas mediante configuracion local aislada. Borrar buffers mutables sensibles al terminar cuando la API lo permita; no prometer eliminar Strings de memoria.
6. Evolucionar UserRepository a buscar por username con PreparedStatement. Mantener hash en un modelo interno de credenciales distinto de UserRecord/DTO HTTP. AuthenticationService verifica la contrasena usando PasswordHasher y devuelve fallo de autenticacion si no coincide, sin llevar logica de negocio al resource.
7. Agregar regresiones: migracion de todos los usuarios, mismos metadatos, ausencia de columna/datos legibles en la tabla activa, salts distintos para passwords iguales, verificacion correcta/incorrecta, formato corrupto rechazado de forma controlada, idempotencia, rollback y login posterior. Probar caracteres Unicode y passwords largos para detectar cambios de semantica. Volver a ejecutar las pruebas de SQL de H-01.

### Archivos previstos

- Modificar [src/main/java/com/bcp/workshop/auth/LegacyDatabase.java](src/main/java/com/bcp/workshop/auth/LegacyDatabase.java), UserRepository.java y AuthenticationService.java propuestos en H-01, y [src/main/resources/application.properties](src/main/resources/application.properties) para configuracion de inicializacion.
- Agregar PasswordHasher.java y un modelo interno UserCredentials.java bajo src/main/java/com/bcp/workshop/auth/. Agregar PasswordHasherTest.java y LegacyDatabaseTest.java bajo src/test/java/com/bcp/workshop/auth/; ampliar UserRepositoryTest.java y [src/test/java/com/bcp/workshop/auth/AuthResourceTest.java](src/test/java/com/bcp/workshop/auth/AuthResourceTest.java), reutilizando fixtures.
- No requiere dependencia criptografica nueva: las APIs propuestas pertenecen al JDK. El esquema de SQLite se migra solo en la implementacion futura; no tocar data ni respaldos durante esta planificacion.

## Clean Code transversal

- Resource: validacion de entrada con guard clauses y delegacion; servicio: autenticacion; repositorio: JDBC; PasswordHasher: derivar/verificar; TokenService: emitir; DTO: representacion HTTP. No abrir conexiones ni generar tokens dentro del resource.
- Metodos pequenos y de una responsabilidad para validar, consultar, mapear, migrar, derivar y emitir. Extraer apertura de conexion/mapeo o fixtures repetidos solo donde elimine duplicacion real.
- Nombres de dominio, no nombres genericos. Sin else anidados para el flujo de login. Constantes con nombre para SQL, duracion, parametros criptograficos, roles y valores contractuales repetidos; usar codigos HTTP con nombre.
- Cambios revisables por hallazgo, sin reordenar ni reformatear codigo ajeno al alcance. La separacion minima de capas facilita las tres correcciones; no se declara H-07 resuelto sin una revision propia.

## Secuencia propuesta y trazabilidad

Esta lista ordena trabajo futuro, no replica estados de HALLAZGOS.md.

| Paso | Hallazgo | Trabajo previo y entrega prevista |
|---|---|---|
| 1 | H-01, H-03, H-02 | Confirmar compatibilidad de token y conflictos contractuales; fijar JDK 21, SQLite aislada y la linea base de caracterizacion antes de editar produccion. |
| 2 | H-01 | Ejecutar caracterizacion; separar capas minimas y parametrizar SQL; ejecutar regresiones de consultas y contrato. |
| 3 | H-03 | Solo con compatibilidad confirmada: ejecutar caracterizacion del token, integrar emisor firmado/configuracion y verificar firma/claims/tiempo; coordinar evidencia externa. Si esta bloqueado, no impide abordar H-02. |
| 4 | H-02 | Ejecutar caracterizacion de esquema/semillas; incorporar hashing, migracion y busqueda por username; ejecutar pruebas de rollback, reinicio y login. |
| 5 | H-01, H-03, H-02 | Validar contrato, cobertura y diff; actualizar unicamente la matriz con evidencia al completar cada hallazgo en la fase de implementacion. |

## Validacion y criterios de salida futuros

1. Ejecutar `mvn test` antes de cada cambio y tras cada hallazgo. Las caracterizaciones seguras deben pasar primero; las regresiones del defecto pueden comenzar fallando y solo pasar tras la correccion. No mantener pruebas que exijan conservar SQL inseguro, secretos en tokens o passwords legibles.
2. Ejecutar `mvn verify` con JDK 21, conservar JaCoCo activo y comprobar al menos 85% de cobertura del codigo modificado. El reporte no equivale por si solo a un umbral automatico: revisar la cobertura de las clases tocadas y no afirmar cumplimiento sin evidencia. No silenciar CodeQL ni Dependency Review.
3. Verificar que las pruebas usan SQLite y claves aisladas, no requieren otros componentes, mantienen los campos/mensajes contractuales y no registran credenciales. Inyectar reloj y dependencias de prueba sin agregar endpoints ni librerias de mocking innecesarias.
4. Mantener libre 8081 para las pruebas Quarkus; si esta ocupado, identificar el propietario y coordinar su parada, no matar procesos ajenos automaticamente. No omitir cobertura para eludir errores de compatibilidad con otro JDK.
5. Comparar `git diff -- docs/ENDPOINTS.md` y su hash antes/despues de la implementacion para demostrar que el archivo no cambia. Revisar el diff de produccion para evitar cambios a CORS, mensajes de error o funcionalidades no solicitadas.
6. H-01 requiere regresiones de inyeccion aprobadas; H-02 requiere migracion y verificacion de hashes aprobadas; H-03 requiere pruebas criptograficas y acuerdo/evidencia de consumidores. Sin esa evidencia no actualizar a resuelto la fila correspondiente.

No se han escrito ni ejecutado estas pruebas como parte de esta entrega de planificacion. No se incorporan dependencias ni se modifica el estado de ningun hallazgo.

### Aprobaciones
Aprobador: Rodrigo Rea Barreto
Fecha: 19/10/2026
Hallazgos Seleccionados:[H-01, H-03 y H-02]