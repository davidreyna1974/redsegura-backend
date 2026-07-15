# language: es
Característica: Gestión del inventario de dispositivos de red
  Como usuario de redSegura
  Quiero registrar, consultar, buscar y dar de baja dispositivos
  Para mantener el inventario como fuente de verdad de la red

  Escenario: Un administrador registra y consulta un dispositivo (RF-01, RF-02)
    Dado que estoy autenticado como "Administrador"
    Cuando registro un dispositivo con serie "FCW-001", hostname "SW-CORE-1" e IP "10.0.0.11"
    Entonces el dispositivo se registra correctamente
    Y al consultarlo aparece en el inventario con hostname "SW-CORE-1"

  Escenario: Un administrador da de baja un dispositivo (RF-02)
    Dado que estoy autenticado como "Administrador"
    Y he registrado un dispositivo con serie "FCW-002", hostname "SW-OLD" e IP "10.0.0.12"
    Cuando doy de baja el dispositivo
    Entonces su estado pasa a "BAJA"

  Escenario: Búsqueda de dispositivos por hostname (RF-04)
    Dado que estoy autenticado como "Administrador"
    Y he registrado un dispositivo con serie "FCW-003", hostname "RT-EDGE-1" e IP "10.0.0.13"
    Cuando busco dispositivos por hostname "edge"
    Entonces obtengo 1 dispositivo en los resultados

  Escenario: Registro de un dispositivo con dirección IPv6 (RF-05a)
    Dado que estoy autenticado como "Administrador"
    Cuando registro un dispositivo con serie "FCW-004", hostname "SW-V6" e IPv6 "2001:db8:acad:1::11"
    Entonces el dispositivo se registra correctamente
    Y su dirección de gestión IPv6 queda almacenada

  Escenario: Un auditor no puede registrar dispositivos (control de acceso por rol)
    Dado que estoy autenticado como "Auditor"
    Cuando registro un dispositivo con serie "FCW-005", hostname "SW-X" e IP "10.0.0.15"
    Entonces la operación es rechazada por falta de permisos

  Escenario: El auditor ve la dirección de gestión enmascarada (seguridad de datos)
    Dado que estoy autenticado como "Administrador"
    Y he registrado un dispositivo con serie "FCW-006", hostname "SW-SEC" e IP "10.0.0.16"
    Cuando un auditor consulta el dispositivo
    Entonces la dirección de gestión IPv4 aparece enmascarada como "10.0.0.***"
