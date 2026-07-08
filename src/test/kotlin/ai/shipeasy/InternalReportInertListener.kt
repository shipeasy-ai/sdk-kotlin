package ai.shipeasy

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

/**
 * Suite-wide safety net: pin [InternalReport]'s baked ingest key to the inert
 * [InternalReport.PLACEHOLDER_KEY] before ANY test runs.
 *
 * The published SDK bakes a REAL public ingest key into [InternalReport] so it
 * can self-report SDK-internal errors to Shipeasy's own project. Left as-is
 * during the SDK's OWN test run, any test that builds a reporting-enabled engine
 * and trips one of [Engine]'s last-resort guards (e.g. the "fail-safe read"
 * tests) would fire a real `POST https://api.shipeasy.ai/collect` and pollute our
 * production Errors dashboard from CI.
 *
 * Registered via `META-INF/services/org.junit.platform.launcher.LauncherSessionListener`,
 * this runs exactly once when the JUnit Platform launcher session opens — before
 * test discovery and before every test class, in the full-suite run AND when a
 * single test class is run in isolation. It resets the key to the placeholder, so
 * `InternalReport.keyConfigured()` returns false and every `report()` short-
 * circuits to a no-op for the whole run.
 *
 * The internal-report unit tests ([InternalReportTest]) set their OWN fake key
 * via `setIngestKeyForTest` in their per-test `@BeforeTest` and use an injected
 * (non-network) sender, so this session-level reset does not interfere with them —
 * it only makes the DEFAULT state during the run inert.
 */
class InternalReportInertListener : LauncherSessionListener {
    override fun launcherSessionOpened(session: LauncherSession) {
        // Referencing InternalReport here also triggers its class init (which sets
        // the real baked key); we immediately override it back to the placeholder.
        InternalReport.setIngestKeyForTest(InternalReport.PLACEHOLDER_KEY)
        // Declare the whole suite production-equivalent for EGRESS. As of 0.16.0 the
        // network + telemetry defaults are environment-derived (OFF outside prod —
        // see Env.isProductionEnv). Tests run in a non-production JVM, so without
        // this every engine built without an explicit isNetworkEnabled would be
        // offline and its network-path assertions (see()/track()/telemetry senders)
        // would break. Pinning the `shipeasy.env` system property to "production"
        // keeps the default ON for the suite; the dedicated EnvDefaultsTest clears
        // it locally to assert the dev/prod branching. Mirrors sdk-ts's
        // src/__tests__/setup.ts (`SHIPEASY_ENV=production`).
        System.setProperty("shipeasy.env", "production")
    }
}
