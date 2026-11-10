package pl.marcin.openclaw.hermes

import org.json.JSONArray
import org.json.JSONObject
import pl.marcin.openclaw.attachment.VisionImage

object HermesRequestBuilder {
    fun userMessage(text: String, images: List<VisionImage>): JSONObject {
        if (images.isEmpty()) return JSONObject().put("role", "user").put("content", text)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        images.forEach { image ->
            content.put(JSONObject().put("type", "text").put("text", "Attachment: ${image.label}"))
            content.put(JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject()
                    .put("url", "data:${image.mimeType};base64,${image.contentBase64}")
                    .put("detail", "high")))
        }
        return JSONObject().put("role", "user").put("content", content)
    }
}
