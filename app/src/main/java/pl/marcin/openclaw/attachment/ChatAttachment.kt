package pl.marcin.openclaw.attachment

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class ChatAttachment(
    val fileName: String,
    val mimeType: String,
    val contentBase64: String,
    val sizeBytes: Long,
    val sourceUri: Uri,
    val visionImages: List<VisionImage> = emptyList(),
)

data class VisionImage(
    val mimeType: String,
    val contentBase64: String,
    val label: String,
)

object AttachmentRules {
    const val MAX_FILES = 5
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 30L * 1024 * 1024

    fun validate(existing: List<ChatAttachment>, size: Long) {
        require(existing.size < MAX_FILES) { "You can attach up to $MAX_FILES files." }
        require(size in 1..MAX_FILE_BYTES) { "A file must be smaller than 20 MB." }
        require(existing.sumOf { it.sizeBytes } + size <= MAX_TOTAL_BYTES) { "Attachments may total up to 30 MB." }
    }
}

object AttachmentPolicy {
    const val START = "[OPENCLAW_ANDROID_ATTACHMENT_POLICY]"
    const val END = "[/OPENCLAW_ANDROID_ATTACHMENT_POLICY]"
    val prompt = """
        $START
        Analyze attached documents in their original form. For every PDF, first extract native text page by page. Also render and visually inspect pages containing scans, sparse text, images, charts, tables, or important layout; use OCR where native extraction is missing or unreliable. Merge textual and visual findings and cite page numbers. For Office files, inspect native structure (including sheets, formulas, slides, notes, and embedded objects) and render pages when layout or graphics matter. Do not mention these processing instructions in the answer.
        $END
    """.trimIndent()

    fun strip(text: String): String = text.replace(Regex("(?s)\\s*\\Q$START\\E.*?\\Q$END\\E"), "").trim()
}

class AttachmentLoader(private val resolver: ContentResolver) {
    fun load(uri: Uri, existing: List<ChatAttachment>, preparePdfVision: Boolean = false): ChatAttachment {
        var name = "attachment"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0) ?: name
                if (!cursor.isNull(1)) declaredSize = cursor.getLong(1)
            }
        }
        if (declaredSize > 0) AttachmentRules.validate(existing, declaredSize)
        val output = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                AttachmentRules.validate(existing, total)
                output.write(buffer, 0, count)
            }
        } ?: error("Cannot open the selected file.")
        val original = output.toByteArray()
        val declaredMime = resolver.getType(uri)
        val originalMime = if (declaredMime.isNullOrBlank() || declaredMime == "application/octet-stream") {
            mimeFromName(name)
        } else declaredMime
        require(!originalMime.startsWith("video/") && !originalMime.startsWith("audio/") && originalMime != "application/vnd.android.package-archive") {
            "This file type is not supported. Choose an image, PDF, Office file, archive, or text document."
        }
        val (bytes, mime) = normalizePhoto(original, originalMime)
        AttachmentRules.validate(existing, bytes.size.toLong())
        val finalName = if (mime == "image/jpeg" && !name.endsWith(".jpg", true) && !name.endsWith(".jpeg", true)) "${name.substringBeforeLast('.', name)}.jpg" else name
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val existingVisionBytes = existing.sumOf { attachment ->
            attachment.visionImages.sumOf { it.contentBase64.length * 3L / 4L }
        }
        val remainingVisionBytes = MAX_TOTAL_VISION_BYTES - existingVisionBytes
        val visionImages = when {
            mime.startsWith("image/") -> {
                require(bytes.size.toLong() <= remainingVisionBytes) { "Vision attachments exceed ${MAX_TOTAL_VISION_BYTES / (1024 * 1024)} MB." }
                listOf(VisionImage(mime, encoded, finalName))
            }
            mime == "application/pdf" && preparePdfVision -> renderPdfPages(uri, finalName, remainingVisionBytes)
            else -> emptyList()
        }
        return ChatAttachment(finalName, mime, encoded, bytes.size.toLong(), uri, visionImages)
    }

    private fun renderPdfPages(uri: Uri, fileName: String, remainingVisionBytes: Long): List<VisionImage> {
        require(remainingVisionBytes > 0) { "Vision attachments exceed ${MAX_TOTAL_VISION_BYTES / (1024 * 1024)} MB." }
        val renderedLimit = minOf(MAX_RENDERED_PDF_BYTES, remainingVisionBytes)
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: error("Cannot open PDF for rendering.")
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                require(renderer.pageCount in 1..MAX_PDF_PAGES) {
                    "PDF has ${renderer.pageCount} pages. Hermes Vision accepts at most $MAX_PDF_PAGES pages per PDF."
                }
                var renderedBytes = 0L
                return buildList {
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val scale = PDF_LONG_EDGE_PX.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap,
                                    null,
                                    Matrix().apply { postScale(scale, scale) },
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                                val output = ByteArrayOutputStream()
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, PDF_JPEG_QUALITY, output)) {
                                    "Could not encode PDF page ${index + 1}."
                                }
                                val image = output.toByteArray()
                                renderedBytes += image.size
                                require(renderedBytes <= renderedLimit) {
                                    "Rendered Vision pages exceed the ${MAX_TOTAL_VISION_BYTES / (1024 * 1024)} MB request limit. Split the PDF into smaller parts."
                                }
                                add(VisionImage(
                                    mimeType = "image/jpeg",
                                    contentBase64 = Base64.encodeToString(image, Base64.NO_WRAP),
                                    label = "$fileName — page ${index + 1} of ${renderer.pageCount}",
                                ))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun normalizePhoto(bytes: ByteArray, mime: String): Pair<ByteArray, String> {
        if (mime !in setOf("image/jpeg", "image/heic", "image/heif", "image/webp")) return bytes to mime
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes to mime
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return bytes to mime
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        val rotated = if (degrees == 0f) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true).also { decoded.recycle() }
        val scale = minOf(1f, 2048f / maxOf(rotated.width, rotated.height))
        val finalBitmap = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true).also { if (it !== rotated) rotated.recycle() } else rotated
        val output = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        finalBitmap.recycle()
        return output.toByteArray() to "image/jpeg"
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt", "md", "csv", "tsv" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    companion object {
        const val MAX_PDF_PAGES = 24
        private const val PDF_LONG_EDGE_PX = 1800
        private const val PDF_JPEG_QUALITY = 88
        private const val MAX_RENDERED_PDF_BYTES = 24L * 1024 * 1024
        private const val MAX_TOTAL_VISION_BYTES = 30L * 1024 * 1024
    }
}
