package com.airsense.companion

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Security

class SshCompatibilityTest {
    @Test fun `SSH client initializes with the packaged crypto providers`() {
        Security.addProvider(BouncyCastleProvider())
        SSHClient().use { client ->
            assertTrue(client.transport.clientVersion.contains("SSHJ"))
            println("SSH implementation used for this build: ${client.transport.clientVersion}")
        }
    }
    @Test fun `host fingerprints use the SSH wire key not X509 bytes`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encoded = Buffer.PlainBuffer().putPublicKey(pair.public).compactData
        val input = ByteBuffer.wrap(encoded)
        val length = input.int
        val algorithm = ByteArray(length)
        input.get(algorithm)
        assertEquals("ssh-rsa", String(algorithm))
        assertFalse(pair.public.encoded.contentEquals(encoded))
    }
}
