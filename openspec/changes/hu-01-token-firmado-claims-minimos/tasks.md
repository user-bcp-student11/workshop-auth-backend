# Tasks

## 1. Caracterizacion del comportamiento actual (antes de cambio funcional)

- [x] 1.1 Ejecutar baseline focalizado de login (`mvn -Dtest=AuthResourceTest,AuthResourceErrorTest,LoginResponseTest test`) y verificar verde para congelar 200/400/401/500 y shape actual de respuesta.
- [x] 1.2 Agregar/ajustar pruebas de caracterizacion para documentar estado actual de `client` (omitido/null/vacio aceptado) y verificar que pasan antes de cambiar reglas.
- [x] 1.3 Agregar/ajustar prueba de caracterizacion del token legado (sin firma, formato actual) y verificar que pasa como evidencia de delta intencional.

## 2. Contrato nuevo de autenticacion y emision firmada

- [x] 2.1 Introducir una frontera de emision de token (abstraccion/adaptador) sin alterar respuestas HTTP actuales y verificar con `mvn -Dtest=AuthResourceTest,LoginResponseTest test`.
- [x] 2.2 Implementar emision de token firmado con claims minimos `sub`, `client`, `iat`, `exp` y verificar con nuevas pruebas unitarias/de recurso que inspeccionen claims.
- [x] 2.3 Cambiar validacion de `client` a **obligatorio no vacio** (decision PO) y verificar escenario 400 cuando falta/es vacio y 200 cuando es valido en `AuthResourceTest`.
- [x] 2.4 Mantener `debug` sin cambios en HU-01 (decision PO) y verificar que los tests de contrato de respuesta exitosa siguen pasando.

## 3. Coherencia temporal, secreto de firma y observabilidad segura

- [x] 3.1 Alinear `expiresIn` con `exp - now` usando tolerancia documentada y verificar con pruebas deterministas de tiempo (sin flakes).
- [x] 3.2 Migrar configuracion de firma para usar `AUTH_TOKEN_SECRET` segun entorno (sin secreto hardcodeado en uso normal) y verificar con pruebas/config de entorno de test.
- [x] 3.3 Revisar trazas/logs del flujo de login para evitar exposicion de token completo o secreto y verificar con pruebas o aserciones sobre salida registrada.

## 4. Cierre del cambio y validacion integral

- [x] 4.1 Actualizar contrato/documentacion de `POST /auth/login` en `docs/ENDPOINTS.md` para reflejar token firmado, claims minimos y nueva regla de `client`; verificar consistencia contra specs.
- [x] 4.2 Ejecutar suite focalizada post-cambio (`mvn -Dtest=AuthResourceTest,AuthResourceErrorTest,LoginResponseTest test`) y verificar verde.
- [x] 4.3 Ejecutar validacion OpenSpec del cambio (`openspec validate hu-01-token-firmado-claims-minimos`) y verificar resultado valido sin advertencias de coherencia.

## Workflow follow-up

- Ejecutar `/opsx-apply hu-01-token-firmado-claims-minimos` para implementar el checklist de manera incremental.
- Tras implementar y verificar evidencia, preparar archive del cambio OpenSpec.
