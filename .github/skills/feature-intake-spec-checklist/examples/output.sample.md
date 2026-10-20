## Summary
Fortalecer el flujo de `POST /auth/login` para emitir un token firmado con claims minimos obligatorios (`sub`, `client`, `iat`, `exp`), alinear `expiresIn` con la expiracion real (`exp - now`) bajo tolerancia documentada, y reforzar el manejo seguro de secretos y logs.

## Scope
- Dentro del alcance: Exigir claims `sub`, `client`, `iat` y `exp`; obtener `client` desde la solicitud, validarlo y reflejarlo en el token; comprobar coherencia de `expiresIn` con `exp - now` usando tolerancia documentada; tratar el secreto por defecto fuera de sandbox; evitar logs de token, secreto y valores sensibles; documentar decision tecnica HMAC vs libreria JWT.
- Fuera del alcance: La HU no confirma cambios de infraestructura, rediseño de API fuera de `POST /auth/login`, ni cambios de arquitectura adicionales.

## Supuestos
- El endpoint afectado es `POST /auth/login`, ya que es el unico explicitado en la HU.
- La respuesta del login mantiene los campos `token`, `expiresIn` y `user`, segun el contexto de la HU.
- La tolerancia de comparacion entre `expiresIn` y `exp - now` debe quedar documentada durante la implementacion.

## Decisiones tecnicas por tomar (pendientes o resueltas)
- Resuelta por la HU: Deben existir los claims `sub`, `client`, `iat` y `exp` en token valido.
- Resuelta por la HU: Se debe evitar registrar token, secreto y valores sensibles en logs.
- Pendiente: Definir si se conserva HMAC o se adopta una libreria JWT.
- Pendiente: Definir la tolerancia exacta para validar `expiresIn` contra `exp - now`.
- Pendiente: Definir como se tratara el secreto por defecto fuera de sandbox (eliminarlo o documentarlo como exclusivo de laboratorio).

## Riesgos y mitigaciones
- Riesgo: Exposicion de token, secreto u otros valores sensibles en logs.
- Mitigacion: Aplicar redaccion/omision de campos sensibles y validar evidencia de logs redactados.
- Riesgo: Incoherencia temporal entre `expiresIn` y `exp - now` por diferencias de reloj o latencia.
- Mitigacion: Definir tolerancia documentada y cubrir con pruebas especificas de expiracion.
- Riesgo: Validacion insuficiente del `client` recibido en solicitud.
- Mitigacion: Definir reglas de validacion del `client` y cubrir casos validos e invalidos en pruebas.

## Criterios de aceptacion (medibles)
- Todo token valido contiene `sub`, `client`, `iat` y `exp`.
- `expiresIn` es coherente con `exp - now` dentro de la tolerancia definida y documentada.
- Existen pruebas para login valido, credenciales invalidas y token expirado.
- No se registran tokens, secretos ni valores sensibles en logs.
- La decision sobre HMAC frente a una libreria JWT queda documentada.

## Plan de validacion
- Pruebas unitarias: Verificar presencia de claims minimos; validar regla de coherencia `expiresIn` vs `exp - now` con tolerancia definida; validar manejo del `client` segun reglas acordadas.
- Pruebas funcionales: Ejecutar escenarios de login valido, credenciales invalidas y token expirado.
- Seguridad (comprobaciones minimas): Revisar logs del flujo para confirmar ausencia de token, secreto y valores sensibles; verificar documentacion de `AUTH_TOKEN_SECRET`.
- Evidencia requerida (que adjuntar): Resultados de pruebas de claims, expiracion y errores de autenticacion; configuracion documentada de `AUTH_TOKEN_SECRET`; evidencia de logs redactados; documento de decision HMAC vs JWT.

## Tareas sugeridas por rol
- Chapter/PO: Confirmar alcance final de HU-01 y aprobar criterios de aceptacion.
- Chapter/PO: Validar que la evidencia esperada quede incluida en la entrega.
- Chapter/PO: Aprobar el criterio de tolerancia una vez definido por el equipo tecnico.
- Tech Lead: Definir y documentar la decision HMAC vs libreria JWT.
- Tech Lead: Definir tolerancia de expiracion y lineamientos de validacion de `client`.
- Tech Lead: Definir politica para secreto por defecto fuera de sandbox y lineamientos de logging seguro.
- Backend: Implementar claims obligatorios (`sub`, `client`, `iat`, `exp`) en la emision de token.
- Backend: Implementar validacion de `client` desde solicitud y coherencia `expiresIn` vs `exp - now`.
- Backend: Asegurar que no se registren token/secreto/valores sensibles en logs.
- Frontend: Alinear solicitud de login para incluir `client` conforme al contrato definido.
- Frontend: Ajustar manejo de errores de autenticacion segun respuesta del backend.
- Frontend: Verificar que trazas del cliente no expongan token ni datos sensibles.
- QA: Diseñar y ejecutar pruebas de login valido, credenciales invalidas y token expirado.
- QA: Verificar claims minimos y coherencia de expiracion bajo tolerancia definida.
- QA: Validar evidencia de logs redactados y consolidar reporte de resultados.
- Cyber: Revisar configuracion y uso seguro de `AUTH_TOKEN_SECRET`.
- Cyber: Auditar ausencia de secretos/tokens en logs y emitir recomendaciones.
- Cyber: Validar cumplimiento minimo de controles de seguridad definidos para el flujo.

## Preguntas abiertas
- ¿Se mantiene HMAC o se adopta una libreria JWT?
- ¿Cual es la tolerancia exacta para validar `expiresIn` frente a `exp - now`?
- ¿Cuales son las reglas exactas de validacion del campo `client` (obligatoriedad, formato, errores)?
- ¿Fuera de sandbox se elimina siempre el secreto por defecto o se permite solo como configuracion de laboratorio documentada?
- ¿Que codigos/mensajes exactos debe devolver el sistema en credenciales invalidas y token expirado, dado que la HU no los especifica?