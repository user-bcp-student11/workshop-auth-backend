# Design

## Context

Este diseno implementa el *how* del cambio definido en `proposal.md` y `specs/auth-login/spec.md` para HU-01 (Issue #12), sin alterar alcance funcional fuera de `POST /auth/login`.

Estado tecnico actual relevante:
- `AuthResource` valida `request`, `username` y `password`; delega autenticacion y retorna 200/400/401/500 en JSON.
- `AuthenticationService` autentica contra `UserRepository` y emite token mediante `LegacyTokenUtil.create(...)`.
- `LegacyTokenUtil` genera token Base64 con formato legado `username|role|expiry|SECRET`.
- `LoginResponse` fija `expiresIn = 3600` y devuelve `token`, `expiresIn`, `user`, `debug`.
- `LoginRequest` ya expone `client`, pero hoy no participa en autenticacion/emision.

Restricciones de contrato baseline (Dia 1):
- Se preserva `POST /auth/login` y la semantica general de autenticacion.
- Se preservan los estados HTTP documentados para validacion/autenticacion fallida.
- El cambio permitido por HU-01 se concentra en semantica del token y uso de `client`.

## Goals / Non-Goals

**Goals:**
- Emitir token firmado con claims minimos observables: `sub`, `client`, `iat`, `exp`.
- Definir una regla clara de validacion de `client` (aceptacion/rechazo) y su reflejo en el token.
- Mantener `expiresIn` por compatibilidad y hacerlo coherente con `exp`.
- Preservar contratos de error (400/401/500) y evitar exposicion de secretos/tokens en logs.
- Introducir el cambio de forma incremental y verificable con pruebas de caracterizacion previas.

**Non-Goals:**
- Redisenar toda la autenticacion o cambiar arquitectura de persistencia.
- Crear nuevos endpoints de validacion/introspeccion de token.
- Cambiar contratos de endpoints distintos de `POST /auth/login`.
- Definir en este documento detalles de rollout operativo fuera del backend de este repositorio.

## Contratos protegidos

1. **Ruta y metodo**
   - Se mantiene `POST /auth/login`.

2. **Familias de respuesta**
   - 200 para autenticacion valida.
   - 400 para request invalido.
   - 401 para credenciales no validas.
   - 500 para excepciones inesperadas del flujo.

3. **Shape de respuesta exitosa**
   - Se preservan `token`, `expiresIn`, `user` (y `debug` mientras siga en el contrato actual).
   - `expiresIn` permanece en segundos como contrato publico.

4. **Compatibilidad semantica de autenticacion**
   - Se conserva precedencia actual de validaciones de entrada (`username` antes que `password`).
   - El cambio de `client` solo afecta la nueva regla de validacion definida por HU-01.

## Componentes afectados

- `LoginRequest`: fuente del campo `client` para validacion de entrada.
- `AuthResource`: validaciones de request y mapeo de errores HTTP.
- `AuthenticationService`: orquestacion de autenticacion + emision token.
- `LegacyTokenUtil` (a evolucionar o reemplazar): emision de token firmado con claims.
- `LoginResponse`: garantia de coherencia entre `expiresIn` y expiracion real declarada en token.
- Configuracion de secreto (`AUTH_TOKEN_SECRET`): fuente de firma y restricciones de entornos.
- Suite de pruebas:
  - `AuthResourceTest` (contrato HTTP y payload).
  - `AuthResourceErrorTest` (errores 500/defensivos).
  - `LoginResponseTest` (shape de respuesta y compatibilidad de campos).

## Decisiones tecnicas y alternativas consideradas

### D1. Migrar de token legado Base64 a token firmado con claims minimos
- **Decision:** emitir token firmado con claims `sub`, `client`, `iat`, `exp`, manteniendo `expiresIn` en respuesta.
- **Rationale:** cumple requerimientos de integridad y expiracion observable sin romper el endpoint.
- **Alternativas:**
  - Mantener Base64 legado agregando campos: rechazado, no aporta firma ni integridad.
  - Cambiar inmediatamente todo el contrato de respuesta: rechazado por riesgo de ruptura de clientes.

### D2. Uso de `client` como dato contractual y no decorativo
- **Decision:** `client` deja de ser ignorado; se valida con reglas explicitas y se incorpora al claim.
- **Rationale:** evita ambiguedad de emision y permite trazabilidad por consumidor.
- **Alternativas:**
  - Seguir aceptando `client` null/vacio: rechazado, contradice HU-01.
  - Remover `client` del request: rechazado, rompe DTO/cliente actual sin necesidad.

### D3. Fuente de secreto de firma via configuracion
- **Decision:** priorizar `AUTH_TOKEN_SECRET` de configuracion; no depender de secreto hardcodeado en produccion.
- **Rationale:** evita exposicion y facilita rotacion/segregacion por entorno.
- **Alternativas:**
  - Mantener secreto fijo en codigo: rechazado por riesgo de seguridad.
  - Permitir fallback inseguro silencioso en cualquier entorno: rechazado; solo aceptable si queda explicitamente restringido a laboratorio/sandbox.

### D4. Preservar respuesta de negocio y endurecer observabilidad
- **Decision:** mantener contrato de errores y shape de salida, pero redaccion de logs sin token completo ni secreto.
- **Rationale:** compatibilidad funcional + mejora de seguridad operacional.
- **Alternativas:**
  - Eliminar campo `debug` ahora: diferido, no requerido por HU-01 y potencialmente rompedor.
  - Cambiar 401/500 existentes en esta HU: rechazado por fuera de alcance.

## Estrategia incremental

1. **Caracterizacion previa (sin cambio funcional):**
   - Congelar comportamiento actual de `POST /auth/login` con pruebas de contrato.
   - Registrar explicitamente el comportamiento actual de `client` (hoy opcional/null/vacio aceptado) para evidenciar el delta intencional.

2. **Introduccion del emisor firmado detras de frontera interna:**
   - Incorporar abstraccion de emision (adaptador) para permitir migrar `LegacyTokenUtil` sin redisenar todo el servicio.
   - Validar que el flujo 200 conserva shape de respuesta esperado.

3. **Activar validacion de `client` y claims minimos:**
   - Aplicar reglas de aceptacion/rechazo de `client` segun spec.
   - Asegurar inclusion de claims obligatorios y coherencia `expiresIn` vs `exp`.

4. **Endurecimiento de configuracion y observabilidad:**
   - Alinear lectura de secreto y mensajes/logs sin datos sensibles.
   - Verificar no regresiones en 400/401/500 ni en campos de respuesta esperados.

## Pruebas de caracterizacion requeridas antes de modificar comportamiento critico

Antes de modificar la emision de token o validacion de `client`, deben existir y estar en verde pruebas que congelen:
- Contrato HTTP actual de `POST /auth/login`:
  - exito (200),
  - validacion de entrada (400),
  - credenciales invalidas (401),
  - error inesperado (500).
- Shape de respuesta exitosa actualmente observable (`token`, `expiresIn`, `user`, `debug`).
- Precedencia de validaciones (`username` requerido antes de `password`).
- Comportamiento actual de `client` (acepta omitido/null/vacio) para luego actualizarlo deliberadamente y demostrar el cambio esperado por HU-01.

## Riesgos / trade-offs

- **[Riesgo]** Ruptura de clientes por cambio semantico de token (de legado Base64 a firmado).  
  **→ Mitigacion:** conservar endpoint y shape; documentar claramente el nuevo token y validar consumidores de laboratorio.

- **[Riesgo]** Cambios en validacion de `client` aumentan respuestas 400.  
  **→ Mitigacion:** definir regla precisa en spec, pruebas de contrato y comunicacion de compatibilidad.

- **[Riesgo]** Desalineacion entre `expiresIn` y `exp` por tiempos de emision/serializacion.  
  **→ Mitigacion:** tolerancia de verificacion documentada y pruebas temporales deterministas.

- **[Trade-off]** Mantener `debug` por compatibilidad puede conservar deuda de contrato.  
  **→ Mitigacion:** no tocar en HU-01; registrar como candidato para hardening posterior.

- **[Trade-off]** Mantener 500 con detalle de excepcion preserva comportamiento pero puede filtrar internals.  
  **→ Mitigacion:** fuera de alcance en esta HU; registrar mejora futura sin romper baseline.

## Rollback

Si el despliegue del token firmado produce fallas funcionales en login:
1. Revertir a la version previa del backend en la rama/deploy objetivo.
2. Restaurar emision legado de token y validacion previa de `client` (comportamiento baseline).
3. Confirmar recuperacion con pruebas de contrato de `POST /auth/login` (200/400/401/500).

El rollback aplica solo a este endpoint y no requiere cambios de esquema de datos.

## Open Questions

- ¿La regla final de `client` sera "requerido no vacio" o un conjunto permitido de identificadores?  
  Esta definicion debe cerrarse antes de implementar porque cambia criterios de aceptacion 400/200.

- ¿Se mantiene `debug` sin cambios o se versiona su semantica al adoptar token firmado?  
  Puede mantenerse por compatibilidad en HU-01, pero conviene decision explicita para evitar ambiguedad en consumidores.
