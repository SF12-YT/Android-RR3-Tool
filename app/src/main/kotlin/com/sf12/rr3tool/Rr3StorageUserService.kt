package com.sf12.rr3tool

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

class Rr3StorageUserService : IRr3StorageService.Stub() {
    override fun getUid(): Int = android.os.Process.myUid()

    override fun findRr3Depot(): String {
        val storageRoots = listOf("/storage/emulated/0", "/mnt/user/0/primary", "/data/media/0")
        for (storageRoot in storageRoots) {
            val androidData = File(storageRoot, "Android/data")
            val packages = androidData.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedWith(
                    compareBy<File> {
                        when (it.name.lowercase()) {
                            "com.ea.games.r3_row" -> 0
                            "com.ea.games.r3_na" -> 1
                            "com.ea.games.r3_cn" -> 2
                            else -> 3
                        }
                    }.thenBy { it.name }
                )
                ?: continue
            for (packageFolder in packages) {
                val depot = File(packageFolder, "files/.depot")
                if (depot.isDirectory && hasAtLeastOneFile(depot)) return depot.canonicalPath
            }
        }
        return ""
    }

    override fun listFiles(depotPath: String): ParcelFileDescriptor {
        val depot = validateDepot(depotPath)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                depot.walkTopDown().filter { it.isFile }.forEach { file ->
                    output.write(file.canonicalPath.toByteArray(StandardCharsets.UTF_8))
                    output.write('\n'.code)
                }
            }
        }, "rr3-shizuku-list").apply { isDaemon = true }.start()
        return pipe[0]
    }

    override fun openFile(depotPath: String, filePath: String): ParcelFileDescriptor {
        val depot = validateDepot(depotPath)
        val file = File(filePath).canonicalFile
        val rootPrefix = depot.canonicalPath.trimEnd(File.separatorChar) + File.separator
        if (!file.path.startsWith(rootPrefix) || !file.isFile) {
            throw SecurityException("The requested file is outside the selected RR3 .depot folder.")
        }
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    FileInputStream(file).use { input -> input.copyTo(output, 256 * 1024) }
                }
            } catch (error: Exception) {
                Log.e("RR3StorageService", "Failed to stream RR3 file", error)
                runCatching { pipe[1].close() }
            }
        }, "rr3-shizuku-read").apply { isDaemon = true }.start()
        return pipe[0]
    }

    private fun validateDepot(depotPath: String): File {
        val depot = File(depotPath).canonicalFile
        val validPath = Regex(
            "^(/storage/emulated/[0-9]+|/mnt/user/[0-9]+/primary|/data/media/[0-9]+)/Android/data/[^/]+/files/\\.depot$"
        ).matches(depot.path)
        if (!validPath || !depot.isDirectory) {
            throw SecurityException("Only an installed RR3 Android/data/.../files/.depot folder can be accessed.")
        }
        return depot
    }

    private fun hasAtLeastOneFile(depot: File): Boolean =
        runCatching { depot.walkTopDown().any { it.isFile } }.getOrDefault(false)

    override fun destroy() {
        System.exit(0)
    }
}
