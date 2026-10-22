package ai.opencode.ide.jetbrains.api.models

import com.google.gson.*
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

/**
 * Base class for OpenCode SSE events.
 */
sealed class OpenCodeEvent {
    abstract val type: String
}

/**
 * Event fired when a session produces new diffs.
 */
data class SessionDiffEvent(
    @SerializedName("type")
    override val type: String = "session.diff",
    val properties: SessionDiffProperties
) : OpenCodeEvent()

data class SessionDiffProperties(
    val sessionID: String,
    val diff: List<FileDiff>
)

/**
 * Event fired when a session becomes idle (conversation turn complete).
 */
data class SessionIdleEvent(
    @SerializedName("type")
    override val type: String = "session.idle",
    val properties: SessionIdleProperties
) : OpenCodeEvent()

data class SessionIdleProperties(
    val sessionID: String
)

/**
 * Event fired when a file is edited.
 */
data class FileEditedEvent(
    @SerializedName("type")
    override val type: String = "file.edited",
    val properties: FileEditedProperties
) : OpenCodeEvent()

data class FileEditedProperties(
    val file: String
)

/**
 * Event fired when session status changes (busy/idle/retry).
 */
data class SessionStatusEvent(
    @SerializedName("type")
    override val type: String = "session.status",
    val properties: SessionStatusProperties
) : OpenCodeEvent()

data class SessionStatusProperties(
    val sessionID: String,
    val status: SessionStatusType
)

/**
 * Event fired when session is updated.
 */
data class SessionUpdatedEvent(
    @SerializedName("type")
    override val type: String = "session.updated",
    val properties: SessionUpdatedProperties
) : OpenCodeEvent()

data class SessionUpdatedProperties(
    val info: Session
)

/**
 * Event fired when assistant produces/updates a message part.
 */
data class MessagePartUpdatedEvent(
    @SerializedName("type")
    override val type: String = "message.part.updated",
    val properties: MessagePartUpdatedProperties
) : OpenCodeEvent()

data class MessagePartUpdatedProperties(
    val part: JsonElement,
    val delta: String? = null
)

/**
 * Event fired when a message part is removed.
 */
data class MessagePartRemovedEvent(
    @SerializedName("type")
    override val type: String = "message.part.removed",
    val properties: MessagePartRemovedProperties
) : OpenCodeEvent()

data class MessagePartRemovedProperties(
    val sessionID: String,
    val messageID: String,
    val partID: String
)

/**
 * Event fired when a message is updated.
 */
data class MessageUpdatedEvent(
    @SerializedName("type")
    override val type: String = "message.updated",
    val properties: MessageUpdatedProperties
) : OpenCodeEvent()

data class MessageUpdatedProperties(
    val info: MessageInfo,
    val sessionID: String? = null
)

data class MessageInfo(
    val id: String,
    val sessionID: String? = null,
    val role: String? = null,
    val type: String? = null
)

/**
 * Event fired when a command is executed.
 */
data class CommandExecutedEvent(
    @SerializedName("type")
    override val type: String = "command.executed",
    val properties: CommandExecutedProperties
) : OpenCodeEvent()

data class CommandExecutedProperties(
    val name: String,
    val sessionID: String,
    val arguments: String,
    val messageID: String
)

data class SessionStepEndedEvent(
    @SerializedName("type")
    override val type: String = "session.step.ended",
    val properties: SessionStepEndedProperties
) : OpenCodeEvent()

data class SessionStepEndedProperties(
    val sessionID: String,
    val messageID: String? = null
)

/**
 * Unknown event type - for events we don't specifically handle.
 */
data class UnknownEvent(
    override val type: String
) : OpenCodeEvent()

/**
 * Custom Gson deserializer for OpenCodeEvent.
 * Routes to the appropriate event class based on the "type" field.
 */
class OpenCodeEventDeserializer : JsonDeserializer<OpenCodeEvent> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): OpenCodeEvent {
        val jsonObject = decodeJson(json).takeIf { it.isJsonObject }?.asJsonObject
            ?: return UnknownEvent("unknown")
        val eventType = jsonObject.stringValue("type", "event") ?: return UnknownEvent("unknown")
        val payload = eventPayload(jsonObject)

        return when (eventType) {
            "session.diff" -> SessionDiffEvent(eventType, context.deserialize(payload, SessionDiffProperties::class.java))
            "session.idle" -> SessionIdleEvent(eventType, context.deserialize(payload, SessionIdleProperties::class.java))
            "session.status" -> deserializeStatus(eventType, payload, context)
            "session.updated" -> SessionUpdatedEvent(eventType, context.deserialize(withNested(payload, "info"), SessionUpdatedProperties::class.java))
            "file.edited" -> FileEditedEvent(eventType, context.deserialize(payload, FileEditedProperties::class.java))
            "message.updated" -> MessageUpdatedEvent(eventType, context.deserialize(withNested(payload, "info"), MessageUpdatedProperties::class.java))
            "message.part.updated" -> MessagePartUpdatedEvent(eventType, context.deserialize(withNested(payload, "part"), MessagePartUpdatedProperties::class.java))
            "message.part.removed" -> MessagePartRemovedEvent(eventType, context.deserialize(payload, MessagePartRemovedProperties::class.java))
            "command.executed" -> CommandExecutedEvent(eventType, context.deserialize(payload, CommandExecutedProperties::class.java))
            "session.step.ended" -> SessionStepEndedEvent(eventType, deserializeStepEnded(payload))
            else -> UnknownEvent(eventType)
        }
    }

    private fun deserializeStatus(
        eventType: String,
        payload: JsonObject,
        context: JsonDeserializationContext
    ): SessionStatusEvent {
        val normalized = payload.deepCopy()
        val status = normalized.get("status")
        if (status != null && status.isJsonPrimitive) {
            normalized.add("status", JsonObject().apply { addProperty("type", status.asString) })
        }
        return SessionStatusEvent(eventType, context.deserialize(normalized, SessionStatusProperties::class.java))
    }

    private fun deserializeStepEnded(payload: JsonObject): SessionStepEndedProperties {
        val sessionId = payload.stringValue("sessionID", "sessionId") ?: ""
        val messageId = payload.stringValue("messageID", "messageId")
        return SessionStepEndedProperties(sessionId, messageId)
    }

    private fun withNested(payload: JsonObject, field: String): JsonObject {
        if (payload.get(field)?.isJsonObject == true) return payload
        return JsonObject().apply { add(field, payload) }
    }

    private fun eventPayload(event: JsonObject): JsonObject {
        var payload = event
        repeat(4) {
            val nested = payload.get("data")?.let(::decodeJson)
                ?: payload.get("properties")
                ?: return payload
            if (!nested.isJsonObject) return payload
            payload = nested.asJsonObject
        }
        return payload
    }

    private fun decodeJson(element: JsonElement): JsonElement {
        var decoded = element
        repeat(3) {
            if (!decoded.isJsonPrimitive || !decoded.asJsonPrimitive.isString) return decoded
            decoded = try {
                JsonParser.parseString(decoded.asString)
            } catch (_: JsonParseException) {
                return decoded
            }
        }
        return decoded
    }

    private fun JsonObject.stringValue(vararg names: String): String? = names
        .firstNotNullOfOrNull { name -> get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
}
