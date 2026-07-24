# language: es
Característica: Respaldo y versionado de configuraciones de red
  Como operador de la red
  Quiero respaldar, versionar y vigilar las configuraciones de los dispositivos
  Para poder auditarlas, comparar cambios y restaurarlas, dentro del alcance autorizado

  Escenario: Respaldo exitoso de un dispositivo dentro del alcance autorizado
    Dado un dispositivo "SW-1" con IP de gestión "10.0.0.5" en el inventario
    Cuando un operador solicita el respaldo del dispositivo
    Entonces el respaldo se crea con estado "SUCCESS"

  Escenario: Se rechaza el respaldo de un dispositivo fuera del alcance autorizado
    Dado un dispositivo "SW-9" con IP de gestión "8.8.8.8" en el inventario
    Cuando un operador solicita el respaldo del dispositivo
    Entonces la solicitud se rechaza por estar fuera del alcance autorizado

  Escenario: Un auditor no puede lanzar respaldos
    Dado un dispositivo "SW-1" con IP de gestión "10.0.0.5" en el inventario
    Cuando un auditor solicita el respaldo del dispositivo
    Entonces el acceso es denegado

  Escenario: Se detecta un cambio de configuración fuera de control (drift)
    Dado un dispositivo "SW-1" con IP de gestión "10.0.0.5" en el inventario
    Y existe un respaldo previo con la configuración "hostname SW-1"
    Cuando la configuración en vivo cambia a "hostname HACKEADO"
    Y un operador ejecuta la verificación de drift
    Entonces se reporta drift en el dispositivo
