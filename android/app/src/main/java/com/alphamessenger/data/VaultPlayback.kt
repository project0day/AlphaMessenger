package com.alphamessenger.data

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher

/** Players read sealed media (see [MediaVault]) decrypted, with seeking; anything else as usual. */
@OptIn(UnstableApi::class)
object VaultPlayback {
    fun sources(context: Context): MediaSource.Factory = DefaultMediaSourceFactory(Factory(context))

    class Factory(context: Context) : DataSource.Factory {
        private val upstream = DefaultDataSource.Factory(context.applicationContext)
        override fun createDataSource(): DataSource = SealedDataSource(upstream.createDataSource())
    }

    private class SealedDataSource(private val upstream: DataSource) : DataSource {
        private var raf: RandomAccessFile? = null
        private var cipher: Cipher? = null
        private var left = 0L
        private var uri: Uri? = null
        private var delegated = false

        override fun addTransferListener(transferListener: TransferListener) =
            upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val u = dataSpec.uri
            val file = u.takeIf { it.scheme == null || it.scheme == "file" }?.path?.let(::File)
            if (file == null || !MediaVault.isSealed(file)) {
                delegated = true
                return upstream.open(dataSpec)
            }
            delegated = false
            uri = u
            val total = file.length() - MediaVault.HEADER_LEN
            val at = dataSpec.position
            if (at > total) throw java.io.EOFException("position $at beyond $total")
            raf = RandomAccessFile(file, "r").apply { seek(MediaVault.HEADER_LEN + at) }
            cipher = MediaVault.cipherAt(MediaVault.fileKey(MediaVault.saltOf(file)), at)
            left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, total - at) else total - at
            return left
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (delegated) return upstream.read(buffer, offset, length)
            if (length == 0) return 0
            if (left == 0L) return C.RESULT_END_OF_INPUT
            val n = raf!!.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (n < 0) return C.RESULT_END_OF_INPUT
            cipher!!.update(buffer, offset, n, buffer, offset)
            left -= n
            return n
        }

        override fun getUri(): Uri? = if (delegated) upstream.uri else uri

        override fun close() {
            if (delegated) {
                upstream.close()
            } else {
                runCatching { raf?.close() }
                raf = null
                cipher = null
                uri = null
            }
            delegated = false
        }
    }
}
