package ai.opencode.ide.jetbrains.api.models

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FileDiffDeserializerTest {
    private val gson = GsonBuilder()
        .registerTypeAdapter(FileDiff::class.java, FileDiffDeserializer())
        .create()

    @Test
    fun reconstructsV2BeforeAndAfterContentsFromUnifiedPatch() {
        val diff = deserialize(
            patch = "@@ -1,2 +1,2 @@\n keep\n-old\n+new\n"
        )

        assertEquals("src/Main.kt", diff.file)
        assertEquals("keep\nold\n", diff.before)
        assertEquals("keep\nnew\n", diff.after)
    }

    @Test
    fun ignoresLegacyBeforeAndAfterFieldsWhenV2PatchIsPresent() {
        val diff = deserialize(
            patch = "@@ -1 +1 @@\n-old\n+new\n",
            before = "legacy before",
            after = "legacy after"
        )

        assertEquals("old\n", diff.before)
        assertEquals("new\n", diff.after)
    }

    private fun deserialize(patch: String, before: String? = null, after: String? = null): FileDiff {
        val json = JsonObject().apply {
            addProperty("file", "src/Main.kt")
            addProperty("patch", patch)
            addProperty("additions", 1)
            addProperty("deletions", 1)
            addProperty("status", "modified")
            before?.let { addProperty("before", it) }
            after?.let { addProperty("after", it) }
        }
        return gson.fromJson(json, FileDiff::class.java)
    }
}
