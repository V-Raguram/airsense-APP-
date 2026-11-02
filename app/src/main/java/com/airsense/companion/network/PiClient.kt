package com.airsense.companion.network

import com.airsense.companion.data.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class PiClient(private val store: SettingsStore, private val network: LocalNetwork) {
    companion object {
        init {
            // Android's cut-down BC provider does not implement all SSH algorithms.
            if (Security.getProvider("BC")?.javaClass != BouncyCastleProvider::class.java) {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            }
        }
        const val MAX_DOWNLOAD = 256L * 1024 * 1024
    }

    private fun <T> connected(settings: DeviceSettings, block: (SSHClient) -> T): T {
        var challenge: HostChallenge? = null
        SSHClient().use { ssh ->
            ssh.connectTimeout = 8000
            ssh.timeout = 15000
            ssh.socketFactory = network.socketFactory()
            ssh.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val raw = Buffer.PlainBuffer().putPublicKey(key).compactData
                    val actual = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw))
                    val trusted = store.fingerprint(settings.host, settings.sshPort)
                    if (trusted != actual) {
                        challenge = HostChallenge(settings.host, settings.sshPort, actual, trusted != null)
                        return false
                    }
                    return true
                }
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            try { ssh.connect(settings.host, settings.sshPort) }
            catch (error: Exception) { challenge?.let { throw HostVerificationRequired(it) }; throw error }
            if (settings.privateKey.isNotBlank()) {
                val provider = ssh.loadKeys(settings.privateKey, null,
                    net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(settings.keyPassphrase.toCharArray()))
                ssh.authPublickey(settings.username, provider)
            } else ssh.authPassword(settings.username, settings.password)
            return block(ssh)
        }
    }

    fun test(settings: DeviceSettings) = connected(settings) { ssh ->
        ssh.newSFTPClient().use { it.stat(settings.logDirectory) }
        Unit
    }

    fun logs(settings: DeviceSettings): List<RemoteLog> = connected(settings) { ssh ->
        ssh.newSFTPClient().use { sftp ->
            sftp.ls(settings.logDirectory).filter { it.isRegularFile && logNameAllowed(it.name) }
                .map { RemoteLog(it.name, it.attributes.size, it.attributes.mtime) }
                .sortedByDescending { it.name }
        }
    }

    /** Read-only SFTP snapshot bounded by the open file's initial length. */
    fun download(settings: DeviceSettings, log: RemoteLog, directory: File, progress: (Long, Long) -> Unit): File {
        require(logNameAllowed(log.name)) { "Invalid Airsense log name." }
        directory.mkdirs()
        val temporary = File(directory, ".${log.name}.${UUID.randomUUID()}.part")
        try {
            connected(settings) { ssh ->
                ssh.newSFTPClient().use { sftp ->
                    sftp.open(settings.logDirectory.trimEnd('/') + "/" + log.name, setOf(OpenMode.READ)).use { remote ->
                        val length = remote.length()
                        require(length <= MAX_DOWNLOAD) { "This log is larger than the 256 MB download limit." }
                        val buffer = ByteArray(64 * 1024)
                        var offset = 0L
                        temporary.outputStream().use { output ->
                            while (offset < length) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                val read = remote.read(offset, buffer, 0, minOf(buffer.size.toLong(), length - offset).toInt())
                                check(read > 0) { "The log was truncated during download. Refresh and retry." }
                                output.write(buffer, 0, read)
                                offset += read
                                progress(offset, length)
                            }
                        }
                        check(remote.length() >= length) { "The log was truncated during download. Retry." }
                    }
                }
            }
            // The writer may have been halfway through its final CSV row.
            // Keep every original byte up to the final complete line.
            trimIncompleteCsvTail(temporary)
            val destination = File(directory, log.name)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return destination
        } finally { temporary.delete() }
    }

    private fun command(ssh: SSHClient, command: String): String = ssh.startSession().use { session ->
        // No PTY and no interactive shell: .bashrc's dashboard auto-attach is bypassed.
        session.exec(command).use { process ->
            val output = process.inputStream.readNBytesCompat(256 * 1024)
            process.join(12, TimeUnit.SECONDS)
            check(process.exitStatus == 0) {
                val error = process.errorStream.readNBytesCompat(4096).toString(Charsets.UTF_8).trim()
                error.ifBlank { "Cannot read the tmux dashboard. Check that its existing session is running." }
            }
            output.toString(Charsets.UTF_8)
        }
    }

    fun panes(settings: DeviceSettings): List<TerminalPane> = connected(settings) { ssh ->
        command(ssh, "tmux list-panes -a -F '#{pane_id}\t#{session_name}:#{window_index}.#{pane_index}\t#{pane_current_command}'")
            .lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3 || !Regex("%[0-9]+").matches(parts[0])) null
                else TerminalPane(parts[0], parts[1], parts[2])
            }.toList()
    }

    fun screen(settings: DeviceSettings, pane: TerminalPane): String = connected(settings) { ssh ->
        require(Regex("%[0-9]+").matches(pane.id))
        command(ssh, "tmux capture-pane -p -e -t ${shellQuote(pane.id)}")
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val bytes = ByteArray(8192)
    while (true) {
        val count = read(bytes)
        if (count < 0) break
        require(result.size() + count <= limit) { "Remote output exceeded the size limit." }
        result.write(bytes, 0, count)
    }
    return result.toByteArray()
}
