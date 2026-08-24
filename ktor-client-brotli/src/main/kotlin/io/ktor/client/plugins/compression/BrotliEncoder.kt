package io.ktor.client.plugins.compression

import io.ktor.util.ContentEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlin.coroutines.CoroutineContext
import org.brotli.dec.BrotliInputStream

private const val EncodingUnsupported =
    "Brotli encoding is not supported: the reference implementation ships a decoder only " +
        "(https://github.com/google/brotli/issues/715)"

internal object BrotliEncoder : ContentEncoder {
    override val name: String = "br"

    override fun encode(source: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        error(EncodingUnsupported)

    override fun encode(source: ByteWriteChannel, coroutineContext: CoroutineContext): ByteWriteChannel =
        error(EncodingUnsupported)

    override fun decode(source: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        BrotliInputStream(source.toInputStream()).toByteReadChannel(coroutineContext)
}
