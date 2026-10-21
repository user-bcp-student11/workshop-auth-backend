# Plan de correccion del backlog pendiente

## Alcance y aprobacion

Propuesta para H-03, H-04, H-05, H-06 y H-07 (H07 en la solicitud) de [HALLAZGOS.md](HALLAZGOS.md). Un apartado y un alcance por item. H-01 y H-02 ya estan resueltos y no se reabren. El seguimiento de estados sigue exclusivamente en HALLAZGOS.md.

Esta entrega solo actualiza PLAN.md. No modifica codigo, pruebas, dependencias, datos, configuracion, contrato ni backlog. Los archivos nuevos mencionados son propuestas para la fase de implementacion, no archivos creados ahora.

**Aprobacion historica conservada:** Rodrigo Rea Barreto, 19/10/2026, seleccion H-01, H-03 y H-02 del plan anterior. En aquella implementacion se confirmo dejar H-03 pendiente por compatibilidad externa no verificada. Esa aprobacion no se extiende automaticamente a esta revision ni a H-04/H-05/H-06/H-07. Este plan requiere nueva aprobacion antes de implementar.

Referencias: [docs/ENDPOINTS.md](docs/ENDPOINTS.md), [docs/GOOD_PRACTICES.md](docs/GOOD_PRACTICES.md) y [REGLAS.md](REGLAS.md). La linea base de pruebas esta documentada en [docs/TESTING.md](docs/TESTING.md); debe ejecutarse de nuevo antes de cualquier cambio, no asumirse vigente por su resultado historico.

## Reglas comunes

- Conservar POST /auth/login, JSON, username/password sin recorte ni normalizacion, client opcional e ignorado, propiedades desconocidas toleradas y orden de validacion.
- Conservar el 200 con token string, expiresIn 3600, user.username/displayName/role y debug actual. Mantener escape HTML4, roles almacenados y ausencia de password/hash/id en la salida.
- Conservar 400 y 401, mensajes y traduccion actual de fallo de consulta a 401. No introducir 503 de SQLite, nuevos endpoints ni autenticacion obligatoria para login/salud.
- Los unicos cambios contractuales propuestos son los explicitamente necesarios para H-03 (contenido/formato del token), H-04 (politica de origen CORS) y H-05 (detalles del 500). Cada apartado delimita su excepcion; ninguno habilita cambios adicionales. No editar ENDPOINTS.md en esta fase.
- Resource solo HTTP; Service coordina negocio; Repository solo persistencia; DTO propio para salida. Metodos de una responsabilidad, guard clauses, nombres de dominio, sin duplicacion ni constantes magicas, conforme a GOOD_PRACTICES.md y REGLAS.md.
- JDK 21, SQLite y claves de pruebas aisladas bajo target o en memoria. No usar secretos reales ni leer/modificar consumidores de otros componentes. Coordinar su compatibilidad con sus responsables.

## Item: H-03 - Token Base64 sin firma

### Item y evidencia actual

H-03, Vulnerabilidad alta, Pendiente en la matriz. [LegacyTokenUtil.java:8](src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L8) fija un secreto y [LegacyTokenUtil.java:14](src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L14) lo incluye en un token Base64 sin firma. [AuthenticationService.java:27](src/main/java/com/bcp/workshop/auth/AuthenticationService.java#L27) usa ese emisor. Hay expiry, pero no se ha verificado su cumplimiento por consumidores.

### Alcance propio y cambio propuesto

1. Sustituir el formato legado por JWT firmado RS256 mediante una biblioteca mantenida, con claims sub, role, iss, iat y exp. No construir JWT ni criptografia manualmente. No cambiar role por groups sin un requisito aprobado.
2. Crear TokenService inyectable y retirar LegacyTokenUtil cuando no tenga referencias. Usar Clock inyectable y una constante de duracion de 3600 segundos compartida con expiresIn. Conservar el DTO HTTP salvo el contenido de token.
3. Consumir una clave de firma y un emisor suministrados por la configuracion de H-04. H-03 es dueno de la emision, algoritmo y claims; no redefine origenes CORS, gestion de configuracion ni respuestas de error.
4. Justificar io.quarkus:quarkus-smallrye-jwt-build como implementacion JOSE probada, con version del BOM que quede aprobado tras H-06. No agregar la extension de autenticacion Bearer si no hay un consumidor local que la necesite. Confirmar la API de verificacion disponible en pruebas y justificar una dependencia de prueba explicita solo si resulta necesaria.
5. Verificar criptograficamente firma y tiempo con claves de laboratorio. No dar por implementada la validacion externa por el solo hecho de emitir JWT; solicitar evidencia de los responsables de consumidores.

### Contrato y decisiones necesarias

El item pide corregir el token y permite proponer sustituir su codificacion insegura, no cambiar el resto del 200. ENDPOINTS.md documenta actualmente Base64 y falta de firma: su apartado de token debera actualizarse junto con la implementacion aprobada, manteniendo el tipo string y expiresIn 3600.

Antes de implementar, confirmar que los consumidores aceptan JWT y coordinar distribucion de clave publica/issuer y rechazo de tokens vencidos. La compatibilidad no esta confirmada; H-03 sigue condicionado. Si el formato legado resulta obligatorio, detener esta correccion y pedir decision contractual. No mantener fallback sin firma ni inventar doble emision, rutas o headers.

### Archivos previstos

- [src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java](src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java): retirada del emisor legado.
- [src/main/java/com/bcp/workshop/auth/AuthenticationService.java](src/main/java/com/bcp/workshop/auth/AuthenticationService.java) y [src/main/java/com/bcp/workshop/auth/LoginResponse.java](src/main/java/com/bcp/workshop/auth/LoginResponse.java): integrar TokenService y duracion compartida.
- Nuevos src/main/java/com/bcp/workshop/auth/TokenService.java y src/test/java/com/bcp/workshop/auth/TokenServiceTest.java; Clock se proporciona en esa clase o su configuracion CDI, sin crear una capa ajena.
- [pom.xml](pom.xml): solo la dependencia de emision justificada; la actualizacion de versiones existentes pertenece a H-06.
- [src/test/java/com/bcp/workshop/auth/AuthResourceTest.java](src/test/java/com/bcp/workshop/auth/AuthResourceTest.java), [docs/ENDPOINTS.md](docs/ENDPOINTS.md) y [docs/TESTING.md](docs/TESTING.md): contrato y evidencias de token, solo tras aprobar la excepcion.

### Prueba antes de tocar codigo

Ejecutar la caracterizacion del 200 completo y 400/401 de AuthResourceTest. Agregar LegacyTokenUtilTest.java temporal para documentar username, role y expiry respecto a la ventana temporal, sin imprimir token/secreto; identificar la exposicion como defecto, no como una asercion permanente de seguridad correcta.

Escribir primero la regresion de integridad: el token legado no satisface una verificacion RS256 ni la ausencia de secreto recuperable. Despues de la correccion, TokenServiceTest debe verificar firma con clave correcta, rechazo con clave equivocada o contenido alterado, algoritmo permitido, claims, exp = iat + 3600 y limites antes/en/despues de exp. La comprobacion temporal usa el verificador de pruebas, no un nuevo endpoint. Registrar fallo antes y aprobacion despues; no afirmar expiracion externa sin evidencia coordinada.

## Item: H-04 - Secreto fijo y CORS permisivo

### Item y evidencia actual

H-04, Vulnerabilidad alta, Pendiente. Secreto en [LegacyTokenUtil.java:8](src/main/java/com/bcp/workshop/auth/LegacyTokenUtil.java#L8); CORS habilitado con origen universal en [application.properties:4](src/main/resources/application.properties#L4) y [application.properties:5](src/main/resources/application.properties#L5). AUTH_DB_URL y las semillas ya estan externalizados; no se rehace ese trabajo.

### Alcance propio y cambio propuesto

1. Definir configuracion tipada y validada para ubicacion de clave de firma y emisor, respaldada por AUTH_TOKEN_SIGN_KEY_LOCATION y AUTH_TOKEN_ISSUER. No valores secretos por defecto ni claves privadas en Git. H-04 provee configuracion; H-03 es el unico dueno del algoritmo y formato del token.
2. Validar ausencia, ilegibilidad o formato incompatible de la clave y emisor vacio sin revelar contenido/rutas sensibles en la respuesta. No suministrar una clave real al codificador Base64 actual: eso la divulgaria en cada token. Activar su consumo solo con el emisor seguro de H-03, coordinando ambos cambios pero manteniendo diffs y pruebas separados.
3. Sustituir el origen universal por una lista explicita configurada mediante AUTH_CORS_ORIGINS, sin inventar dominios o puertos del frontend. Exigir la lista para un despliegue con CORS habilitado y rechazar configuraciones universales; sin configuracion valida no habilitar acceso cross-origin amplio.
4. Mantener metodos/cabeceras permitidos actuales salvo que el analisis de preflight demuestre que deben reducirse y se apruebe expresamente. No agregar allow-credentials, cookies, autenticacion o cambios de host/TLS como parte de este item.

### Contrato y decisiones necesarias

H-04 pide corregir CORS permisivo: se permite proponer el cambio de politica de origen y documentarlo, no cambiar cuerpos/codigos de login o salud. Obtener primero los origenes autorizados del responsable del entorno. Un origen rechazado por CORS puede seguir recibiendo una respuesta HTTP; la prueba debe verificar la ausencia de autorizacion cross-origin, no exigir arbitrariamente HTTP 403.

### Archivos previstos

- [src/main/resources/application.properties](src/main/resources/application.properties): binding de clave/issuer y lista CORS, sin secretos en claro.
- Nuevo src/main/java/com/bcp/workshop/auth/TokenConfiguration.java para carga/validacion. H-03 consume esta configuracion, sin duplicarla.
- Nuevos src/test/java/com/bcp/workshop/auth/TokenConfigurationTest.java y CorsConfigurationTest.java con perfiles aislados.
- [README.md](README.md), [docs/ENDPOINTS.md](docs/ENDPOINTS.md) y [docs/TESTING.md](docs/TESTING.md): variables necesarias, politica aprobada, preflight y limites operativos. No modificar otros componentes.

### Prueba antes de tocar codigo

Agregar caracterizacion HTTP de Origin y preflight OPTIONS sobre /auth/login, incluyendo GET /q/health solo como comprobacion de la politica comun, no como endpoint nuevo. Registrar el comportamiento actual con origenes de laboratorio sin inferir proteccion efectiva de navegadores a partir del codigo de estado.

Escribir primero regresiones que exijan autorizar un origen configurado y no emitir Access-Control-Allow-Origin para uno ajeno; la segunda debe fallar contra la configuracion universal actual. Preparar pruebas de clave/emisor ausentes o invalidos, dos perfiles con claves distintas y mensajes/logs sin secretos. Tras corregir, validar configuracion estricta, origen permitido, origen ajeno y preflight con content-type/authorization, conservando el contrato JSON. No agregar una dependencia solo para leer configuracion que Quarkus ya proporciona.

## Item: H-05 - Detalles internos en respuestas de error

### Item y evidencia actual

H-05, Vulnerabilidad media, Pendiente. La fila original conserva lineas de una revision anterior; evidencia actual: [AuthResource.java:38](src/main/java/com/bcp/workshop/auth/AuthResource.java#L38) captura la excepcion y devuelve getMessage/getClass. Las pruebas HTTP controladas de [AuthResourceErrorTest.java](src/test/java/com/bcp/workshop/auth/AuthResourceErrorTest.java) reproducen esa exposicion.

### Alcance propio y cambio propuesto

1. Mantener HTTP 500 y application/json, pero sustituir el cuerpo por un DTO de error con solo message y un texto generico propuesto: Internal server error. Suprimir exception y el mensaje tecnico original del cliente. No agregar identificadores, campos ni codigos nuevos sin necesidad aprobada.
2. Centralizar el mapeo de excepciones inesperadas que actualmente captura login, con un manejador acotado al resource o una excepcion de dominio especifica; no registrar un ExceptionMapper global que transforme inadvertidamente errores de Quarkus, salud o 400/401.
3. Registrar el diagnostico en el servidor con control de datos sensibles. No loguear LoginRequest, password, hashes, token ni configuracion de claves. No asumir que cualquier mensaje/stacktrace de una dependencia es seguro para log: aplicar redaccion y usar fixtures de excepciones que contengan datos de prueba para verificarla.
4. Mantener 400/401, sus textos y el 401 ante consulta fallida. No cambiar recuperacion de SQLite, formato del token, CORS ni semantica de negociacion del framework.

### Contrato y decisiones necesarias

H-05 pide explicitamente dejar de exponer detalles: el cambio necesario se limita al cuerpo 500. ENDPOINTS.md documenta hoy exception y mensajes variables; aprobar el nuevo ejemplo {"message":"Internal server error"} y coordinar a clientes que lean exception. Si se exige conservar los detalles originales, el item no puede resolverse: pedir decision, no fingir una correccion solo interna.

### Archivos previstos

- [src/main/java/com/bcp/workshop/auth/AuthResource.java](src/main/java/com/bcp/workshop/auth/AuthResource.java): respuesta generica y delegacion del mapeo/logging.
- Nuevo src/main/java/com/bcp/workshop/auth/ErrorResponse.java; un manejador adicional solo si es necesario para centralizar sin ampliar el alcance.
- [src/test/java/com/bcp/workshop/auth/AuthResourceErrorTest.java](src/test/java/com/bcp/workshop/auth/AuthResourceErrorTest.java) y [src/test/java/com/bcp/workshop/auth/AuthResourceTest.java](src/test/java/com/bcp/workshop/auth/AuthResourceTest.java): errores y no regresion de otras ramas.
- [docs/ENDPOINTS.md](docs/ENDPOINTS.md) y [docs/TESTING.md](docs/TESTING.md): solo la excepcion contractual aprobada y su evidencia.

### Prueba antes de tocar codigo

Ejecutar primero las dos pruebas HTTP 500 existentes y la caracterizacion directa, conservando su evidencia previa. Escribir la regresion que espera 500 con solo message generico ante excepcion con mensaje y sin mensaje: debe fallar inicialmente por el cuerpo actual. Tras corregir, reemplazar las expectativas vulnerables, no mantenerlas como requisito permanente.

Agregar captura de log de prueba con un identificador tecnico inocuo y valores sensibles ficticios para comprobar diagnostico util sin credenciales. Reejecutar todos los 400/401, incluido almacenamiento no disponible. No ampliar a los errores de framework que ENDPOINTS.md mantiene abiertos.

## Item: H-06 - Revision SCA de dependencias

### Item y evidencia actual

H-06, Sugerencia media provisional, Pendiente. [pom.xml:13](pom.xml#L13) declara Quarkus 3.27.4 y [pom.xml:50](pom.xml#L50) Commons Text 1.9. El uso actual es escapeHtml4 en [LoginResponse.java:12](src/main/java/com/bcp/workshop/auth/LoginResponse.java#L12), no interpolacion. No hay una CVE ni explotabilidad confirmadas por este plan.

### Alcance propio y cambio propuesto

1. Obtener arbol de dependencias y versiones realmente resueltas, incluidas transitivas; contrastar Dependency Review y advisories oficiales con identificador, rango afectado, version corregida y ruta aplicable. Separar dependencia afectada, exposicion del uso y falta de evidencia; no declarar una vulnerabilidad solo por la antiguedad.
2. Elegir la menor actualizacion mantenida que cierre los advisories aplicables conservando JDK 21. Actualizar Quarkus mediante su BOM/plugin coordinados, no versiones individuales arbitrarias de extensiones.
3. Para Commons Text, preferir actualizar a una version corregida/mantenida con escape HTML4 equivalente. Retirarla solo si el reemplazo demuestra equivalencia completa del valor de displayName; no eliminar el escape ni sustituirlo por concatenaciones caseras. Justificar cualquier dependencia alternativa antes de agregarla.
4. Recompilar, ejecutar pruebas, repetir el analisis SCA y comparar resultados. Si una alerta no aplica al uso observado, registrar la evidencia, no silenciarla. No cambiar CodeQL, Dependency Review o JaCoCo para obtener verde.

### Contrato y decisiones necesarias

No hay cambio contractual autorizado en este item. Mantener todos los campos, escapes, estados y comportamientos documentados. Una actualizacion de framework puede cambiar respuestas previas al resource: capturarlas como observacion, sin convertir las preguntas abiertas en nuevas expectativas arbitrarias. Si una solucion requiere romper un contrato existente, detener esa variante y solicitar decision.

### Archivos previstos

- [pom.xml](pom.xml): BOM y/o version de Commons Text segun evidencia, sin fijar ahora una version futura no investigada.
- [src/main/java/com/bcp/workshop/auth/LoginResponse.java](src/main/java/com/bcp/workshop/auth/LoginResponse.java): solo si se aprueba retirar Commons Text; no es cambio obligatorio para un simple upgrade.
- [src/test/java/com/bcp/workshop/auth/AuthResourceTest.java](src/test/java/com/bcp/workshop/auth/AuthResourceTest.java) y nuevo src/test/java/com/bcp/workshop/auth/LoginResponseTest.java para equivalencia de escape.
- [docs/TESTING.md](docs/TESTING.md) para comandos/reportes; evidencia SCA regenerable bajo target. El resultado y eventual cierre se registran solo en [HALLAZGOS.md](HALLAZGOS.md).

### Prueba antes de tocar codigo

Ejecutar la suite existente sobre las versiones actuales y guardar arbol/analisis previos. Escribir pruebas de caracterizacion de escape HTML4 con ampersand, comillas, etiquetas, caracteres Unicode y nombre vacio; comparar con el comportamiento actual y el DTO completo. No inventar un tratamiento distinto de null para datos que el esquema declara no nulos.

Tras actualizar o sustituir, exigir igualdad de esos resultados y de las respuestas HTTP. Repetir analisis con las mismas fuentes/metodo: el advisory debe desaparecer por version corregida o retirarse la dependencia, no por una exclusion del scanner. Si no se obtiene evidencia suficiente, mantener el item pendiente y documentar exactamente lo no verificado.

## Item: H-07 - Responsabilidades y Clean Code

### Item y evidencia actual

H-07 (H07), Refactor bajo, Pendiente. La descripcion de la matriz es historica: [AuthResource.java:21](src/main/java/com/bcp/workshop/auth/AuthResource.java#L21) ya tiene retornos tempranos y delega a AuthenticationService; hay UserRepository y LoginResponse. No reconstruir SQL ni hashing, ya corregidos por H-01/H-02.

### Alcance propio y cambio propuesto

1. Revisar la separacion actual contra GOOD_PRACTICES.md: resource sin SQL, hashing o emision; servicio coordinador; repositorio JDBC; DTO publico distinto de UserRecord. Determinar el residuo real y actualizar la evidencia al cerrar, no repetir literalmente el hallazgo original.
2. Mantener validacion HTTP en el resource y guard clauses. Extraer metodos de validacion/mapeo solo donde reduzcan complejidad o duplicacion real; no crear capas, interfaces o factories sin necesidad.
3. Revisar construccion repetida de respuestas y constantes contractuales. Usar nombres de dominio y constantes con nombre para valores repetidos; no cambiar mensajes, campos o rutas. Mantener una responsabilidad por metodo y evitar nuevo anidamiento.
4. El acoplamiento al emisor estatico pertenece a H-03, y el mapeo seguro de errores a H-05. H-07 verifica sus limites una vez implementados; no se atribuye esas correcciones ni cambia criptografia, errores, dependencias o configuracion.
5. Si la inspeccion y las pruebas demuestran que ya no hay residuo propio de H-07, no forzar un diff cosmetico: proponer su cierre por verificacion con evidencia actual en la matriz. No declararlo resuelto durante esta planificacion.

### Contrato y decisiones necesarias

Ningun cambio contractual autorizado. La refactorizacion debe ser observablemente equivalente, excepto los cambios ya aprobados y ejecutados en sus propios items. GET /q/health permanece sin handler nuevo ni acceso a SQLite agregado.

### Archivos previstos

- Revision y, solo si queda responsabilidad indebida, cambios pequenos en [src/main/java/com/bcp/workshop/auth/AuthResource.java](src/main/java/com/bcp/workshop/auth/AuthResource.java), [src/main/java/com/bcp/workshop/auth/AuthenticationService.java](src/main/java/com/bcp/workshop/auth/AuthenticationService.java), [src/main/java/com/bcp/workshop/auth/UserRepository.java](src/main/java/com/bcp/workshop/auth/UserRepository.java) y [src/main/java/com/bcp/workshop/auth/LoginResponse.java](src/main/java/com/bcp/workshop/auth/LoginResponse.java). No editar los cuatro por obligacion.
- [src/test/java/com/bcp/workshop/auth/AuthResourceTest.java](src/test/java/com/bcp/workshop/auth/AuthResourceTest.java), [src/test/java/com/bcp/workshop/auth/UserRepositoryTest.java](src/test/java/com/bcp/workshop/auth/UserRepositoryTest.java) y, si falta la prueba de delegacion, nuevo src/test/java/com/bcp/workshop/auth/AuthenticationServiceTest.java.
- [HALLAZGOS.md](HALLAZGOS.md) solo en la fase futura de cierre con evidencia; [docs/TESTING.md](docs/TESTING.md) para resultados, sin reescribir el contrato.

### Prueba antes de tocar codigo

Ejecutar las caracterizaciones HTTP de 200/400/401/500 vigentes en ese momento. Agregar una prueba de servicio con repositorio/emisor de prueba que verifique coordinacion, usuario correcto y ausencia de emision cuando las credenciales no son validas; usar la interfaz real que resulte de H-03 sin volver a implementar su algoritmo.

Mantener las regresiones de SQL, hashes, migracion y no exposicion de DTO interno. Tras cada extraccion, repetir la misma prueba y comparar respuestas. La prueba automatizada no demuestra por si sola una sola responsabilidad: completar con revision del diff, imports y dependencias entre capas, sin agregar una libreria de arquitectura para este refactor pequeno.

## Secuencia de implementacion propuesta

Cada paso pertenece a un unico item; compartir un fichero no combina su alcance ni su evidencia.

| Paso | Item | Entrega propia |
|---|---|---|
| 1 | H-06 | Linea base SCA y decision de versiones; upgrade equivalente si esta justificado. Facilita elegir dependencias de JWT compatibles. |
| 2 | H-04 | Configuracion validada y CORS con origenes aprobados; preparar claves sin entregarlas al emisor legado. |
| 3 | H-03 | Emisor firmado consumiendo esa configuracion, solo tras confirmar compatibilidad externa. |
| 4 | H-05 | Mapeo/logging de 500 seguro con su unica excepcion contractual aprobada. Puede adelantarse si no depende del token. |
| 5 | H-07 | Verificacion de arquitectura actual y refactor residual estrictamente equivalente, o cierre por evidencia sin cambios cosmeticos. |

La configuracion de firma de H-04 y su consumo en H-03 requieren una liberacion coordinada: no dar H-04 por cerrado con una configuracion sin uso efectivo, ni H-03 por cerrado sin evidencia de consumidores. Mantener sus pruebas y notas separadas en la matriz.

## Validacion futura y trazabilidad

- Para cada item: prueba de caracterizacion antes; regresion de correccion primero en rojo cuando corresponda; cambio pequeno; misma regresion en verde y suite completa. No conservar como requisito un defecto que el item autoriza retirar.
- Ejecutar `mvn test` y `mvn verify` con JDK 21. Usar un archivo JaCoCo nuevo por medicion y evaluar CSV/XML: minimo 85% de lineas en cada clase modificada. Declarar por separado instrucciones y ramas; no presentar cobertura de produccion como cobertura del codigo de tests.
- Reutilizar perfiles y SQLite bajo target. No probar contra data/workshop.db, imprimir tokens/claves o detener procesos ajenos para liberar puertos.
- Registrar comandos, directorio, codigo de salida, resultados antes/despues y reportes; actualizar HALLAZGOS.md solo al completar la evidencia del item. No crear otro backlog de estados en este plan.
- Mantener CodeQL, Dependency Review y JaCoCo. No afirmar gates de CI aprobados con solo una ejecucion local. ENDPOINTS.md solo cambia en los puntos explicitamente delimitados y aprobados de H-03, H-04 o H-05; H-06/H-07 deben preservarlo.
- Este plan no corrige las preguntas abiertas de salud ni los errores de framework, no confirma CVEs y no registra pruebas futuras como ejecutadas.

Fuentes consultadas: ENDPOINTS.md, GOOD_PRACTICES.md, REGLAS.md, HALLAZGOS.md, plan anterior y los archivos de produccion citados en cada apartado. No se modifico ningun archivo de codigo ni el estado de los items.


## Aprobaciones
 Aprobador: Rodrigo Rea Barreto
 Fecha: 19/10/2026
 Items: H-03, H-04, H-05, H-06 y H-07