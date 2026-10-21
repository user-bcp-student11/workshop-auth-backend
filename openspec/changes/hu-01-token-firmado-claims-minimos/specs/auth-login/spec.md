# auth-login

## ADDED Requirements

### Requirement: POST /auth/login emite token firmado con claims minimos
`POST /auth/login` MUST mantener su funcion de autenticar usuario y devolver `token`, `expiresIn` y `user`, pero el `token` emitido MUST ser firmado y contener los claims minimos `sub`, `client`, `iat` y `exp`.

#### Scenario: Login valido devuelve token firmado con claims minimos
- **GIVEN** un usuario con credenciales validas
- **AND** una solicitud JSON valida para `POST /auth/login`
- **WHEN** el servicio autentica correctamente
- **THEN** responde `200 OK`
- **AND** la respuesta incluye `token`, `expiresIn` y `user`
- **AND** el `token` contiene los claims `sub`, `client`, `iat` y `exp`
- **AND** `sub` representa al sujeto autenticado
- **AND** `client` corresponde al `client` de la solicitud validada

### Requirement: client deja de ser ignorado y tiene validacion de contrato
El campo `client` del request para `POST /auth/login` MUST dejar de ser ignorado. El contrato MUST definir cuando se acepta y cuando se rechaza para evitar emisiones ambiguas.

#### Scenario: client valido se refleja en el token
- **GIVEN** credenciales validas
- **AND** un `client` valido segun el contrato
- **WHEN** se procesa `POST /auth/login`
- **THEN** el claim `client` del token emitido coincide con el `client` recibido

#### Scenario: client invalido se rechaza como error de validacion
- **GIVEN** una solicitud con `client` ausente o invalido segun el contrato definido
- **WHEN** se procesa `POST /auth/login`
- **THEN** el endpoint responde con error de validacion de entrada (familia 400)
- **AND** no emite token

### Requirement: Coherencia verificable entre expiresIn y exp
Para compatibilidad con el contrato actual, la respuesta MUST conservar `expiresIn`; ademas, `expiresIn` MUST ser coherente con `exp - now` dentro de una tolerancia documentada.

#### Scenario: expiresIn consistente con exp dentro de tolerancia
- **GIVEN** un login exitoso
- **WHEN** se evalua el `exp` del token y el instante de emision observable
- **THEN** la diferencia `exp - now` coincide con `expiresIn` dentro de la tolerancia documentada

### Requirement: Se preservan rutas y estados base no cambiados por HU-01
El cambio MUST mantener coherencia con el baseline de `docs/ENDPOINTS.md` para `POST /auth/login`, excepto en los puntos explicitamente modificados por esta HU (semantica de token y uso de `client`).

#### Scenario: Credenciales invalidas mantienen respuesta de autenticacion fallida
- **GIVEN** credenciales invalidas
- **WHEN** se invoca `POST /auth/login`
- **THEN** se mantiene la respuesta de autenticacion fallida del contrato vigente (401)
- **AND** no se retorna token

#### Scenario: Campos sensibles no se exponen en observabilidad
- **GIVEN** ejecucion normal o con error del flujo de login
- **WHEN** se registran logs de la operacion
- **THEN** no se exponen token completo ni secretos de firma
