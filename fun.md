Okay, this is a complex set of interconnected scripts. I'll break down the functions involved in the process of taking a Cisco CallManager 6.0 CDR entry and getting it into the `acumtotal` table.

The process generally flows like this:

1.  **`cm_procesar.php` (Hacha - Centralized Processor, if used)**:
    *   Reads raw CDR files (potentially from multiple sources/IPs).
    *   Uses a plant-specific "hacha" include (e.g., `hc_cisco_cm.php` or `hc_cisco_cmex.php`) to parse lines and determine which client/directory the CDR belongs to.
    *   Writes these parsed/sorted CDR lines into client-specific directories for `txt2dbv8.php` to pick up.
    *   **Key function here**: `romper_linea()` (defined in the "hacha" include, e.g., `hc_cisco_cm.php` or `IMDex_romper_linea` as a fallback).

2.  **`txt2dbv8.php` (Main CDR to DB Processor)**:
    *   Reads CDR files from a specific client/plant directory.
    *   Includes the plant-specific file (e.g., `tp_cisco_cm_60.php`).
    *   Calls `evaluar_Formato()` from the plant-specific file for each CDR line.
    *   Processes the returned structured data (validates, determines call type, calculates cost).
    *   Inserts the final record into `acumtotal`.

Here's a list of functions, categorized by the file they are primarily defined in or their main purpose. Functions from standard libraries (like `lib_db.php`, `funciones.php`) are generally assumed and might not be explicitly listed unless they play a very direct role.

**I. `tp_cisco_cm_60.php` (Plant-specific for Cisco CM 6.0)**

*   **`evaluar_Formato($string)`**:
    *   This is the **primary entry point** for parsing a single CDR string for this plant type.
    *   Calls `CM_FormatoCDR(CM_6_0, $string)` from `include_cm.php`.

**II. `include_cm.php` (Common Cisco CallManager Routines)**

*   **`CM_FormatoCDR($tipoplanta, $string, $nopar_terminar = false)`**:
    *   The core parsing logic for Cisco CDRs.
    *   Calls `cm_CargarParam()` to load configuration.
    *   Uses `csv_datos()` (likely a utility from `funciones.php` or similar) to split the CDR string.
    *   Calls `CM_ValidarCab()` if the line is a header.
    *   Calls `CM_ReportarNoCabs()` if headers are missing.
    *   Calls `CM_ArregloData()` multiple times to extract specific fields based on header positions.
    *   Calls `dec2ip()` for IP address conversion.
    *   Calls `Fecha_Segundos()` (utility) for time conversions.
    *   Calls `CM_ValidarConferencia()` to check if a number is a conference bridge.
    *   Calls `_cm_CausaTransfer()` to set transfer reasons.
    *   Calls `_invertir()` (utility) to swap caller/callee details if needed.
    *   Calls `ExtensionPosible()` (from `include_captura.php`) to check if a number could be an extension.
*   **`cm_CargarParam($link, $tipoplanta, &$_cm_config)`**:
    *   Loads plant-specific configuration parameters.
    *   Calls `CM_TipoPlanta()`.
*   **`CM_TipoPlanta($link, $tipoplanta, &$cm_config, $bdcliente='', $directorio = '')`**:
    *   Defines CDR field names and their default expected header names.
    *   Calls `CDR_cargarBDD()` (from `modulos/cdr/lib/include_cdr.php`) to load custom configurations from DB.
    *   Calls `CDRCabecerasNuevas()` (from `include_captura.php`) to handle CDR headers.
    *   Calls `CM_ValidarCab()` to map header names to internal field names.
*   **`CM_ValidarCab(&$cm_config, $campos, $informar=true)`**:
    *   Maps the actual CDR header fields (from `$campos`) to the internal configuration (`$cm_config`) used by `CM_ArregloData`.
    *   Uses `csv_limpiar_campos()` (utility).
*   **`CM_ArregloData($arreglo_string, $cm_config, $param)`**:
    *   Retrieves data from the parsed CDR array (`$arreglo_string`) using the field position stored in `$cm_config[$param]`.
*   **`CM_ValidarConferencia($cm_config, $cdr_extension)`**:
    *   Checks if a given extension string matches the conference identifier pattern.
*   **`_cm_CausaTransfer(&$info_arr, $causa)`**:
    *   Sets the `info_transfer` field in the CDR array.
*   **`evaluar_Reprox($link, &$info_arr, $row)`**: (Optional, called during reprocessing)
    *   Custom logic for reprocessing Cisco calls, especially handling conferences.
    *   Calls `buscarConferenciaOrigen()` or `buscarConferenciaPadre()`.
*   **`buscarConferenciaPadre(...)` / `buscarConferenciaOrigen(...)`**:
    *   Queries `acumtotal` to find related conference call legs.
    *   Calls `acumtotal_ConsultaReprox()` (from `include_cuarentena.php`).
    *   Calls `acumtotal_BDDReprox()` (from `txt2dbv8.php`).
*   **`dec2ip($dec)`**: Converts decimal IP to dotted-quad.
*   **`linea_Archivo($fp)`**: Reads a line from the CDR file, skipping "INTEGER" type lines.

**III. `txt2dbv8.php` (Main Processing Logic - excerpts relevant to CDR to `acumtotal`)**

*   **`CargarCDR($tipo, $info, $link)`**:
    *   Orchestrates processing of a batch of CDRs (either from file or reprocessing).
    *   Calls `CargarDatos()` to load general system data.
    *   Calls `ObtenerFuncionarios()` to pre-fetch employee data.
    *   Calls `es_llamada_interna()` to determine if a call is internal.
    *   Calls `ObtenerFuncionario_Arreglo()` to get employee details for a call.
    *   Calls `procesaEntrante()` or `procesaSaliente()` based on call direction.
*   **`procesaEntrante($link, &$info, $resultado_directorio, &$funext)`**:
    *   Handles incoming calls.
    *   Calls `info_interna()`.
    *   Calls `InvertirLlamada()` if needed.
    *   Calls `ValidarOrigenLlamada()`.
    *   Calls `evaluarPBXEspecial()`.
    *   Calls `_esEntrante_60()` (CME specific, but might be called if includes are mixed).
    *   Calls `buscarOrigen()` to determine the origin of the incoming call.
    *   Calls `acumtotal_Insertar()`.
*   **`procesaSaliente($link, $info_cdr, $resultado_directorio, &$funext, $pbx_especial = false)`**:
    *   Handles outgoing calls.
    *   Calls `_es_Saliente()` (CME specific).
    *   Calls `info_interna()`.
    *   Calls `procesaServespecial()` for special numbers.
    *   Calls `procesaInterna()` for internal calls.
    *   Calls `evaluarPBXEspecial()`.
    *   Calls `procesaSaliente_Complementar()` for tariffing.
    *   Calls `acumtotal_Insertar()`.
*   **`procesaInterna($link, &$info, $resultado_directorio, &$funext, $pbx_especial = false)`**:
    *   Processes internal calls.
    *   Calls `limpiar_numero()`.
    *   Calls `IgnorarLlamada()`.
    *   Calls `tipo_llamada_interna()` to determine the specific type of internal call.
    *   Calls `TarifasInternas()`.
    *   Calls `Calcular_Valor()`.
    *   Calls `operador_interno()`.
*   **`procesaServespecial($link, &$info, $resultado_directorio)`**:
    *   Processes calls to special service numbers.
    *   Calls `limpiar_numero()`.
    *   Calls `buscar_NumeroEspecial()`.
    *   Calls `ValidarOrigenLlamada()`.
    *   Calls `Calcular_Valor()`.
    *   Calls `operador_interno()`.
*   **`procesaSaliente_Complementar($link, &$info, $resultado_directorio, $pbx_especial = false)`**:
    *   Complements outgoing call processing, mainly for tariffing.
    *   Calls `ValidarOrigenLlamada()`.
    *   Calls `evaluarDestino()` for tariff calculation.
    *   Calls `Obtener_ValorEspecial()` for special tariffs.
    *   Calls `Calcular_Valor()`.
    *   Calls `Calcular_Valor_Reglas()` for rule-based tariff adjustments.
*   **`acumtotal_Insertar($link, $info, $COMUBICACION_ID, $infovalor)`**:
    *   **The final step**: Inserts the processed CDR data into the `acumtotal` table.
    *   Calls `Complementar_infoValor()`.
    *   Calls `ValorSinIVA()`.
    *   Calls `ExtensionEncontrada()`.
    *   Calls `ActualizarFuncionarios()`.
    *   Calls `CDR_Actual_FileInfoLocal()`.
    *   Calls `evaluar_campos()` (optional custom validation).
    *   Calls `TRM_ConversionMoneda()` and `TRM_Aplicar()` for currency conversion.
    *   Calls `Acumtotal_FormatoCampos()` to format fields for SQL.
    *   Calls `buscarDuplicado()` to check for existing records.
    *   Calls `bd_insert_id()` or handles updates.
    *   Calls `acumtotal_Pos()` (optional post-insert actions).
    *   Calls `ReportarErrores()` if insertion fails.
*   **`acumtotal_BDDReprox($link, $row, $comubica, $incluir_tarifas = false)`**: (During reprocessing)
    *   Converts a row from `acumtotal` back into a CDR-like array structure.
    *   Calls `_tipotele_Internas()`, `_operador_Internas()`.
    *   Calls `Acumtotal_ID()`.
    *   Calls `Reprox_ObtenerVideo()`.
    *   Calls `evaluar_Reprox()` (optional).
*   **Other utility/setup functions in `txt2dbv8.php`**:
    *   `IMDex_AccesoWeb`, `VerificarPaths`, `IMDex_PrepararCarga`, `CargarDirectorio`, `GenerarBackup`, `include_cdrs`, `IMDex_HayArchivos`, `ReportarNoCDRs`, `Reprocesar_Buscar`, `Reprocesar_Ini`, `ActualizarPlanta`, `TerminarProceso`, `defineParamCliente`, `ValidarUso`.

**IV. `include_captura.php` (General Capture Utilities)**

*   **`limpiar_numero($numero, $_PREFIJO_SALIDA_PBX = '', $modo_seguro = false)`**:
    *   Cleans a phone number string, optionally removing PBX prefixes.
    *   Calls `Validar_prefijoSalida()`.
*   **`Validar_prefijoSalida($numero, $_PREFIJO_SALIDA_PBX = '')`**:
    *   Checks if a number starts with a defined PBX exit prefix.
*   **`_duracion_seg($tcadena)`**: Converts HH:MM:SS or MM:SS string to total seconds.
*   **`CargarPrefijos(&$prefijo, $mporigen_id, $link)`**: Loads prefix data from DB.
*   **`CargarTroncales(&$celulink, $comubicacion_id, $link)`**: Loads trunk data.
*   **`CargarIndicativos(&$indicativos, $mporigen_id, $link)`**: Loads area code/series data.
*   **`buscarPrefijo($numero_marcado, $existe_troncal, $mporigen_id, $link)`**:
    *   Finds the best matching prefix for a given number.
    *   Calls `_esCelular_fijo()` (CME specific).
*   **`buscarTroncal($troncal_buscar, $comubicacion_id, $link)`**: Finds trunk details.
*   **`buscarDestino(...)`**:
    *   Determines the destination (city, country) based on the dialed number and call type.
    *   Calls `BuscarIndicativoLocal()`.
    *   Calls `rellenaSerie()`.
    *   Calls `SeriesArreglo()`.
    *   Calls `_ciudad()` (utility).
*   **`buscarValor(...)`**: Gets the base tariff value for a call.
*   **`evaluarPBXEspecial($link, $dial_number, $directorio, $cliente, $incoming = 0)`**:
    *   Checks for special PBX routing rules that might alter the dialed number.
*   **`evaluarDestino($link, $info_destino, $info_co, $info_tiempo, $resultado_directorio, ...)`**:
    *   **Core tariffing function**.
    *   Calls `buscarTroncal()`.
    *   Calls `evaluarDestino_pos()`.
*   **`evaluarDestino_pos(...)`**:
    *   Internal part of `evaluarDestino`.
    *   Calls `ObtenerMaxMinActual()`.
    *   Calls `limpiar_numero()`.
    *   Calls `buscarPrefijo()`.
    *   Calls `buscarOperador_Troncal()`.
    *   Calls `buscarDestino()`.
    *   Calls `BuscarLocalExtendida()`.
    *   Calls `AsignarLocalExtendida()`.
    *   Calls `buscarValor()`.
*   **`Calcular_Valor($duracion, $infovalor)`**: Calculates the final call cost.
    *   Calls `Complementar_infoValor()`.
    *   Calls `Duracion_Minuto()`.
*   **`Calcular_Valor_Reglas(...)`**: Applies rule-based tariff adjustments.
*   **`ObtenerMaxMin($link, $mporigen_id, ...)`**: Gets min/max extension lengths for the plant.
    *   Calls `MaxMinGuardar()`.
    *   Calls `ObtenerExtensionesEspeciales()`.
*   **`Obtener_ValorEspecial(...)`**: Applies special tariffs (e.g., time-of-day, holidays).
    *   Calls `Obtener_InfoFecha()`.
    *   Calls `ArregloHoras()`.
    *   Calls `Guardar_ValorInicial()`.
    *   Calls `ValorSinIVA()`.
*   **`ObtenerFuncionarios($funext_datos, $link)`**: Pre-fetches employee data.
    *   Calls `ObtenerGlobales()`.
    *   Calls `ObtenerHistoricosFuncionarios()`.
*   **`ObtenerFuncionario_Arreglo($link, $ext, $clave, ...)`**: Gets employee for a specific call.
    *   Calls `FunIDValido()`.
    *   Calls `Validar_RangoExt()`.
*   **`es_llamada_interna(&$info, $link)`**: Determines if a call is internal.
    *   Calls `ExtensionPosible()`.
    *   Calls `evaluarPBXEspecial()`.
    *   Calls `Validar_RangoExt()`.
*   **`tipo_llamada_interna(...)`**: Determines the specific type of internal call.
    *   Calls `asignar_ubicacion()`.
    *   Calls `prefijos_OrdenarInternos()`.
*   **Other utility/setup functions in `include_captura.php`**:
    *   `ValidarTelefono`, `Complementar_infoValor`, `IVA_Troncal`, `rellenaSerie`, `SeriesArreglo`, `ValidarFechasHistorico`, `Obtener_HistoricoHasta_Listado`, `Obtener_HistoricoHasta`, `Obtener_RangoExt`, `Validar_RangoExt`, `CargarServEspeciales`, `buscar_NumeroEspecial`, `TipoplantasCom`, `CDRCabeceras`, `CDRCabecerasNuevas`, `CDRCabecerasID`, `CDRCabecerasBDD`, `CDR_capturarIP`, `info_interna`, `InvertirLlamada`, `ExtensionPosible`, `ExtensionEncontrada`, `ExtensionValida`, `_esLocal`, `ObtenerMaxMinActual`.

**V. `include_carga.php` (General Loading Utilities)**

*   **`IMDex_AccesoWeb`, `IMDex_PrepararCarga`, `IMDex_HayArchivos`, `IMDex_ActualizarPlanta`, `IMDex_ReportarNoCDRs`, `IMDex_Leer_Linea`, `CDR_Actual_Abrir`, `CDR_Actual_Suspender`, `IMDex_ArchivosPendientes`, `CDR_Actual_Cerrar`, `CDR_Actual_Avance`, `CDR_Actual_Posicion`, `CDR_Actual_Valor`, `CDR_Actual_Remplazar`, `CDR_Actual_FileInfo`, `CDR_Actual_FileInfoLocal`, `Carga_TiempoSuperado`, `Carga_PosLinea`, `IMDex_Cancelar`, `IMDex_ListaPendientes`, `IMDex_ListaPendientesCM`, `CDR_Actual_InfoComentario`**.
*   **Logging functions**: `logs`, `_print_r`, `_print_q`.
*   **License/Setup functions**: `ValidarEspacioMinimo`, `ValidarLicServicio`, `CargarLicFun`, `ValidarLicFun`, `DescontarLicFun`.
*   **File handling**: `Archivo_TiempoCreado`, `include_cdrs`.

**VI. `include_cuarentena.php` (Quarantine and Error Handling)**

*   **`Acumtotal_ID($row)`**: Gets `ACUMTOTAL_ID` from a row.
*   **`RetirarLlamada($link, &$id_acum_actual, $retirar = true)`**: Marks a call in `acumtotal` as quarantined.
    *   Calls `buscarDuplicado()`.
*   **`buscarDuplicado($link, $acumcampos, $id_acum_actual = 0, $conformato = false)`**:
    *   Checks for duplicate entries in `acumtotal`.
    *   Calls `AcumcamposFormato()`.
*   **`CDRInvalido($link, ...)`**: Inserts a problematic CDR into `acumfallido`.
    *   Calls `CDRCabecerasID()`.
*   **`AcumcamposFormato($key, $value, $conformato = false)`**: Formats a field value for SQL.

**VII. `hc_cisco_cm.php` (Hacha - Cisco CM specific, if used before `txt2dbv8.php`)**

*   **`romper_linea_old(&$f, $linea, &$cm_Datos, $resultado_planta)`**: (Marked as old, but shows the logic)
    *   Parses a raw CDR line to determine its destination client/directory.
    *   Calls `csv_datos()`.
    *   Calls `CDRCabecerasNuevas()`.
    *   Calls `CM_ValidarCab()`.
    *   Calls `CM_ReportarNoCabs()`.
    *   Calls `CM_ArregloData()`.
    *   Calls `CM_ValidarConferencia()`.
    *   Calls `validarParticionExt()`.
    *   Calls `buscarParticion()`.
    *   Calls `buscarPlantaDestino()`.
    *   Calls `GuardarDatosCDR()` (which calls `crearArchivo` and `escribirArchivo`).
*   **`validarParticionExt(...)`**: Validates partition and extension.
*   **`buscarParticion(...)`**: Finds partition information.
    *   Calls `buscarExtensiones()`.
*   **`buscarExtensiones(...)`**: Finds extensions for a plant.
    *   Calls `Obtener_RangoExt()`.
    *   Calls `ObtenerHistoricosFuncionarios()`.
*   **`buscarPlantaDestino(...)`**: Determines the target plant for a CDR based on extension/partition.
*   **`IMDex_romper_linea(...)`**: (More generic version, likely called if `hc_cisco_cm.php`'s `romper_linea` isn't specific enough).
    *   Calls `CDR_capturarIP()`.
    *   Calls `evaluar_Formato()` (from the plant-specific file, e.g., `tp_cisco_cm_60.php`).
    *   Calls `buscarExtensiones()`.
    *   Calls `buscarPlantaDestino()`.

This list is extensive due to the modular nature of the scripts and the various checks, validations, and special case handlings involved. The core path for a single CDR line involves `evaluar_Formato` -> `CM_FormatoCDR` -> (parsing logic) -> return to `txt2dbv8.php` -> `CargarCDR` -> `procesaEntrante/Saliente` -> tariffing functions -> `acumtotal_Insertar`.