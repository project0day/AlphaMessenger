package com.alphamessenger.data

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.buffer
import okio.source
import java.io.File

/** Every picture the app shows through the image loader: a sealed file is read decrypted. */
class VaultImageFetcher(private val file: File, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult = SourceFetchResult(
        source = ImageSource(MediaVault.open(file).source().buffer(), options.fileSystem),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != null && data.scheme != "file") return null
            val file = File(data.path ?: return null)
            return if (MediaVault.isSealed(file)) VaultImageFetcher(file, options) else null
        }
    }
}
