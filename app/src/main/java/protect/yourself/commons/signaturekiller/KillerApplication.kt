package protect.yourself.commons.signaturekiller

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.security.cert.X509Certificate

/**
 * Signature Killer Application
 *
 * Replaces the original `bin.mt.signature.KillerApplication` class that the
 * modified NopoX APK extended. This class hooks into the Android package
 * manager so that calls to `PackageManager.getPackageInfo(..., GET_SIGNATURES)`
 * return a forged signature, allowing the modified APK to pass the original
 * app's signature checks (Firebase, Firebase AppCheck, OAuth, etc.).
 *
 * Usage: extend this class instead of `Application`.
 *
 * Notes:
 *  - On Android 14+ (targetSdk 35+), some hooks may require additional
 *    SELinux exemptions or may not work at all on production devices.
 *  - This class is only necessary if you intend to install the rebuild
 *    alongside services that still verify the original NopoX signature.
 *  - For a fresh install on a clean device, you do NOT need this class —
 *    simply extend `Application` directly.
 *
 * Vendored from: `bin.mt.signature.KillerApplication` (NopoX APK)
 */
abstract class KillerApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            hookPackageManager(base)
        } catch (t: Throwable) {
            // Best-effort hook — never crash the app
            android.util.Log.w("KillerApplication", "Signature hook failed", t)
        }
    }

    /**
     * Returns the forged signature that the hooked PackageManager should report.
     *
     * Default: returns the NopoX original signing certificate (SHA-1 of the
     * original release key). Override to provide a different signature.
     *
     * NOTE: This is intentionally a placeholder. In a production rebuild, you
     * would either:
     *  1. Replace this with `null` and let real signatures pass through, OR
     *  2. Hardcode the original NopoX signing cert if you need to bypass
     *     Firebase AppCheck on the original Firebase project.
     */
    protected open fun getForgedSignature(): X509Certificate? {
        // TODO: populate with original NopoX signing cert if needed for
        // Firebase AppCheck compatibility. Otherwise return null to disable.
        return null
    }

    /**
     * Hook the application's PackageManager via reflection so that
     * `getPackageInfo(...)` with `GET_SIGNATURES` returns the forged cert.
     */
    private fun hookPackageManager(base: Context) {
        val forgedCert = getForgedSignature() ?: return
        try {
            val pm = base.packageManager
            val sPmField: Field? = pm.javaClass.fields.firstOrNull { it.name == "mPM" }
                ?: pm.javaClass.declaredFields.firstOrNull { it.name == "mPM" }

            // Try multiple hook strategies depending on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                hookOnPieAndLater(base, pm, forgedCert)
            } else {
                hookPrePie(pm, sPmField, forgedCert)
            }
        } catch (t: Throwable) {
            android.util.Log.w("KillerApplication", "PM hook failed", t)
        }
    }

    private fun hookPrePie(pm: Any, sPmField: Field?, forgedCert: X509Certificate) {
        // Pre-P: directly replace the mPM field with a proxy
        sPmField?.isAccessible = true
        val originalPm = sPmField?.get(pm) ?: return
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            originalPm.javaClass.classLoader,
            originalPm.javaClass.interfaces
        ) { _, method, args ->
            val result = method.invoke(originalPm, *(args ?: arrayOfNulls(0)))
            rewritePackageInfo(result, forgedCert)
            result
        }
        sPmField.set(pm, proxy)
    }

    private fun hookOnPieAndLater(
        base: Context,
        pm: Any,
        forgedCert: X509Certificate
    ) {
        // On Android 9+ (P), PackageManager is split between IPackageManager
        // and ApplicationPackageManager. Use IApplicationThread + ActivityThread
        // to access the underlying IPackageManager.
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod: Method =
                activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentActivityThreadMethod.isAccessible = true
            val activityThread = currentActivityThreadMethod.invoke(null)

            val sPmField: Field = activityThreadClass.getDeclaredField("sPackageManager")
            sPmField.isAccessible = true
            val originalPm = sPmField.get(activityThread)

            val iPackageManagerClass = Class.forName("android.content.pm.IPackageManager")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                iPackageManagerClass.classLoader,
                arrayOf(iPackageManagerClass)
            ) { _, method, args ->
                val result = method.invoke(originalPm, *(args ?: arrayOfNulls(0)))
                rewritePackageInfo(result, forgedCert)
                result
            }

            // Replace static sPackageManager
            sPmField.set(activityThread, proxy)

            // Also replace the mPM field on this context's ApplicationPackageManager
            val pmImplField = pm.javaClass.declaredFields.firstOrNull { it.name == "mPM" }
            pmImplField?.isAccessible = true
            pmImplField?.set(pm, proxy)
        } catch (t: Throwable) {
            android.util.Log.w("KillerApplication", "P+ hook failed", t)
        }
    }

    /**
     * Inspect the result of getPackageInfo. If it's a PackageInfo with
     * signatures, overwrite them with the forged cert.
     */
    private fun rewritePackageInfo(result: Any?, forgedCert: X509Certificate) {
        if (result == null) return
        try {
            // Handle single PackageInfo
            if (result.javaClass.name == "android.content.pm.PackageInfo") {
                rewriteSinglePackageInfo(result, forgedCert)
            }
            // Handle arrays of PackageInfo
            else if (result is Array<*>) {
                result.forEach { item ->
                    if (item?.javaClass?.name == "android.content.pm.PackageInfo") {
                        rewriteSinglePackageInfo(item, forgedCert)
                    }
                }
            }
        } catch (t: Throwable) {
            // Best-effort
        }
    }

    private fun rewriteSinglePackageInfo(packageInfo: Any, forgedCert: X509Certificate) {
        try {
            val signaturesField = packageInfo.javaClass.getDeclaredField("signatures")
            signaturesField.isAccessible = true
            val signatureClass = Class.forName("android.content.pm.Signature")
            val signatureCtor = signatureClass.getConstructor(ByteArray::class.java)
            val forgedSig = signatureCtor.newInstance(forgedCert.encoded)
            signaturesField.set(packageInfo, arrayOf(forgedSig))
        } catch (t: Throwable) {
            // Best-effort
        }
    }

    /**
     * Helper: load the original NopoX signing certificate from assets.
     * Override [getForgedSignature] to call this if needed.
     */
    @Suppress("unused")
    protected fun loadCertFromAssets(context: Context, assetName: String): X509Certificate? {
        return try {
            val certBytes = context.assets.open(assetName).use { it.readBytes() }
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            cf.generateCertificate(certBytes.inputStream()) as? X509Certificate
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "KillerApplication"
    }
}
