# Propuesta: HU-01 - Token firmado con expiracion y claims minimos

## Referencia
- Issue fuente: #12
- Baseline de comportamiento a preservar: `docs/ENDPOINTS.md` (Dia 1), especialmente `POST /auth/login`.

## Por que importa este cambio
El contrato actual de `POST /auth/login` documenta un token legado Base64 sin firma y con el campo `client` ignorado. Esto limita la trazabilidad del emisor/consumidor y no permite verificar integridad del token de manera estandar.  
El Issue #12 solicita endurecer ese comportamiento para que el token emitido sea firmado, con expiracion verificable y claims minimos observables, manteniendo coherencia con el contrato funcional existente.

## Alcance
- Ajustar el contrato observable de respuesta de `POST /auth/login` para que el `token` emitido incluya claims minimos `sub`, `client`, `iat` y `exp`.
- Definir reglas observables para validacion del campo `client` en la solicitud y su propagacion al token.
- Definir criterio verificable de coherencia entre `expiresIn` y `exp - now` con tolerancia documentada.
- Mantener los escenarios de autenticacion ya documentados (login valido, credenciales invalidas y errores de validacion), actualizando solo lo necesario para reflejar el nuevo token.

## Fuera de alcance
- Diseno tecnico de implementacion (biblioteca especifica, algoritmos concretos, estructura de clases o refactor interno).
- Cambios en endpoints distintos de `POST /auth/login`.
- Introduccion de un endpoint nuevo de introspeccion/validacion remota de token.
- Creacion de plan de tareas (`tasks.md`) o diseno (`design.md`) en esta etapa.

## Comportamiento que debe preservarse
- Se preserva el endpoint `POST /auth/login` y su finalidad de autenticar y devolver identidad del usuario autenticado.
- Se preservan las validaciones de entrada para payload invalido (400) y credenciales no validas (401), salvo ajustes explicitos del contrato de `client`.
- Se preserva el campo `expiresIn` en la respuesta por compatibilidad de cliente, cambiando su semantica para que sea coherente con el `exp` del token firmado.
- Se preserva la necesidad de no exponer secretos ni valores sensibles en logs.

## Impacto funcional esperado (delta del contrato)
- El token deja de ser tratado como token legado Base64 no firmado y pasa a ser un token firmado con claims minimos obligatorios.
- El campo `client` deja de ser "ignorado" y pasa a participar en la emision del token bajo reglas validables.
- La documentacion y pruebas de contrato deberan reflejar estos cambios como comportamiento observable del endpoint.
