package com.infomedia.abacox.telephonypricing.security.permissions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for permission keys declared by this module.
 * <p>
 * The {@code String} constants below are the only values that should appear
 * inside {@code @RequiresPermission(...)}. They are intentionally bare
 * {@code resource:action} keys (no module prefix) - the prefix is applied by
 * {@link com.infomedia.abacox.telephonypricing.component.permissions.PermissionScanner}
 * at registration time so every module's local catalog stays readable.
 * <p>
 * Every constant MUST have a matching entry in {@link #DESCRIPTIONS}. The
 * scanner cross-checks these at startup and logs a warning on drift.
 */
public final class Permissions {

    private Permissions() {}

    // ---- CDR (call detail records) ------------------------------------
    public static final String CDR_CREATE    = "cdr:create";
    public static final String CDR_READ      = "cdr:read";
    public static final String CDR_UPDATE    = "cdr:update";
    public static final String CDR_UPLOAD    = "cdr:upload";
    public static final String CDR_REPROCESS = "cdr:reprocess";

    // ---- Company ------------------------------------------------------
    public static final String COMPANY_CREATE = "company:create";
    public static final String COMPANY_READ   = "company:read";
    public static final String COMPANY_UPDATE = "company:update";

    // ---- Configuration ------------------------------------------------
    public static final String CONFIGURATION_READ   = "configuration:read";
    public static final String CONFIGURATION_UPDATE = "configuration:update";

    // ---- Cost center --------------------------------------------------
    public static final String COST_CENTER_CREATE = "cost-center:create";
    public static final String COST_CENTER_READ   = "cost-center:read";
    public static final String COST_CENTER_UPDATE = "cost-center:update";

    // ---- Dashboard ----------------------------------------------------
    public static final String DASHBOARD_READ = "dashboard:read";

    // ---- Employee -----------------------------------------------------
    public static final String EMPLOYEE_CREATE = "employee:create";
    public static final String EMPLOYEE_READ   = "employee:read";
    public static final String EMPLOYEE_UPDATE = "employee:update";

    // ---- Inventory ----------------------------------------------------
    public static final String INVENTORY_CREATE = "inventory:create";
    public static final String INVENTORY_READ   = "inventory:read";
    public static final String INVENTORY_UPDATE = "inventory:update";

    // ---- Job position -------------------------------------------------
    public static final String JOB_POSITION_CREATE = "job-position:create";
    public static final String JOB_POSITION_READ   = "job-position:read";
    public static final String JOB_POSITION_UPDATE = "job-position:update";

    // ---- Migration ----------------------------------------------------
    public static final String MIGRATION_READ    = "migration:read";
    public static final String MIGRATION_EXECUTE = "migration:execute";

    // ---- Numbering (indicators, series, prefixes, bands, ...) --------
    public static final String NUMBERING_CREATE = "numbering:create";
    public static final String NUMBERING_READ   = "numbering:read";
    public static final String NUMBERING_UPDATE = "numbering:update";

    // ---- Pricing ------------------------------------------------------
    public static final String PRICING_CREATE = "pricing:create";
    public static final String PRICING_READ   = "pricing:read";
    public static final String PRICING_UPDATE = "pricing:update";

    // ---- Reports ------------------------------------------------------
    public static final String REPORTS_READ   = "reports:read";
    public static final String REPORTS_EXPORT = "reports:export";

    // ---- Subdivision --------------------------------------------------
    public static final String SUBDIVISION_CREATE = "subdivision:create";
    public static final String SUBDIVISION_READ   = "subdivision:read";
    public static final String SUBDIVISION_UPDATE = "subdivision:update";

    // ---- Telephony configuration (trunks, rules, plant, comm location)
    public static final String TELEPHONY_CONFIG_CREATE = "telephony-config:create";
    public static final String TELEPHONY_CONFIG_READ   = "telephony-config:read";
    public static final String TELEPHONY_CONFIG_UPDATE = "telephony-config:update";

    /**
     * Immutable mapping of local permission key to human-readable description.
     * Order is preserved for deterministic registration and diffing.
     */
    public static final Map<String, String> DESCRIPTIONS;

    static {
        Map<String, String> m = new LinkedHashMap<>();

        m.put(CDR_CREATE,                "Create CDR (call detail record) entries");
        m.put(CDR_READ,                  "View CDR records and related call data");
        m.put(CDR_UPDATE,                "Modify CDR records");
        m.put(CDR_UPLOAD,                "Upload raw CDR files for processing");
        m.put(CDR_REPROCESS,             "Re-run CDR processing for existing records");

        m.put(COMPANY_CREATE,            "Create companies");
        m.put(COMPANY_READ,               "View companies");
        m.put(COMPANY_UPDATE,             "Modify companies");

        m.put(CONFIGURATION_READ,         "View telephony-pricing module configuration");
        m.put(CONFIGURATION_UPDATE,       "Modify telephony-pricing module configuration");

        m.put(COST_CENTER_CREATE,         "Create cost centers");
        m.put(COST_CENTER_READ,           "View cost centers");
        m.put(COST_CENTER_UPDATE,         "Modify cost centers");

        m.put(DASHBOARD_READ,             "View the telephony-pricing dashboard and summary metrics");

        m.put(EMPLOYEE_CREATE,            "Create employees");
        m.put(EMPLOYEE_READ,              "View employees");
        m.put(EMPLOYEE_UPDATE,            "Modify employees");

        m.put(INVENTORY_CREATE,           "Create inventory items and related records");
        m.put(INVENTORY_READ,             "View inventory items, equipment, owners, and work orders");
        m.put(INVENTORY_UPDATE,           "Modify inventory items and related records");

        m.put(JOB_POSITION_CREATE,        "Create job positions");
        m.put(JOB_POSITION_READ,          "View job positions");
        m.put(JOB_POSITION_UPDATE,        "Modify job positions");

        m.put(MIGRATION_READ,             "View data migration status");
        m.put(MIGRATION_EXECUTE,          "Start or trigger data migrations");

        m.put(NUMBERING_CREATE,           "Create numbering plan entries (indicators, series, prefixes, bands)");
        m.put(NUMBERING_READ,             "View numbering plan entries");
        m.put(NUMBERING_UPDATE,           "Modify numbering plan entries");

        m.put(PRICING_CREATE,             "Create pricing rules and trunk rates");
        m.put(PRICING_READ,               "View pricing rules and trunk rates");
        m.put(PRICING_UPDATE,             "Modify pricing rules and trunk rates");

        m.put(REPORTS_READ,               "View telephony reports");
        m.put(REPORTS_EXPORT,             "Export telephony reports");

        m.put(SUBDIVISION_CREATE,         "Create subdivisions and assign managers");
        m.put(SUBDIVISION_READ,           "View subdivisions and their managers");
        m.put(SUBDIVISION_UPDATE,         "Modify subdivisions and their managers");

        m.put(TELEPHONY_CONFIG_CREATE,    "Create telephony configuration (trunks, rules, comm locations, plant types)");
        m.put(TELEPHONY_CONFIG_READ,      "View telephony configuration");
        m.put(TELEPHONY_CONFIG_UPDATE,    "Modify telephony configuration");

        DESCRIPTIONS = Collections.unmodifiableMap(m);
    }

    /**
     * Look up the description for a local permission key
     * ({@code resource:action}, no module prefix).
     *
     * @return the description, or {@code null} if the key is not cataloged.
     */
    public static String describe(String localKey) {
        return DESCRIPTIONS.get(localKey);
    }
}
