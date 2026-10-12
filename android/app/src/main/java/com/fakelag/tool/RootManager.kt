package com.fakelag.tool

import java.io.DataOutputStream
import java.io.File

/**
 * Root / Admin elevation utility — mirrors the Python admin elevation pattern:
 *
 * Python (Windows):
 *   if not is_admin(): elevate(); sys.exit(0)
 *   Then use kernel-level WinDivert to drop packets.
 *
 * Android equivalent:
 *   Check root access → request su → then use iptables to drop packets.
 *   Falls back to VPN service if no root available.
 */
object RootManager {

    /** Tracks whether su granted root this session */
    var hasRoot = false
        private set

    /** Whether we've attempted elevation */
    var elevationAttempted = false
        private set

    /**
     * Check if `su` binary exists on the device — equivalent to Python's `is_admin()`.
     * Does NOT request root, just probes availability.
     */
    fun isRootAvailable(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/su/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
        )
        return paths.any { File(it).exists() }
    }

    /**
     * Request root elevation — equivalent to Python's `elevate()` which calls
     * `ShellExecuteW(None, "runas", ...)`.
     *
     * Executes `su` which triggers the Superuser grant dialog on rooted devices.
     * Returns true if root was granted.
     */
    fun elevate(): Boolean {
        if (hasRoot) return true
        elevationAttempted = true

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su"))
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            val exitCode = process.waitFor()
            hasRoot = (exitCode == 0)
            hasRoot
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Execute a command as root. Returns the process exit code.
     * Equivalent to running commands after admin elevation in Python.
     */
    fun execRoot(command: String): Int {
        if (!hasRoot) return -1
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
            process.exitValue()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Execute a command as root and return stdout + stderr.
     */
    fun execRootWithOutput(command: String): String {
        if (!hasRoot) return ""
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            process.waitFor()
            stdout.ifEmpty { stderr }
        } catch (e: Exception) {
            e.toString()
        }
    }

    /**
     * Full admin elevation pipeline — mimics Python's:
     *   if not is_admin():
     *       elevate()
     *       sys.exit(0)
     *
     * On Android, this is called once at app start. If root is available but
     * not yet granted, it requests it via su.
     */
    fun ensureElevated(): Boolean {
        if (hasRoot) return true
        if (!isRootAvailable()) return false
        return elevate()
    }

    /** Release root session */
    fun release() {
        hasRoot = false
        elevationAttempted = false
    }
}
