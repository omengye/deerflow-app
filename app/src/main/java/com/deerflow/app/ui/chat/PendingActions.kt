package com.deerflow.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@Composable
internal fun PendingActions(
    permissions: List<JsonObject>, requests: List<JsonObject>, enabled: Boolean, resetKey: Int,
    onPermission: (String, String) -> Unit, onAnswers: (String, JsonArray) -> Unit,
) {
    if (permissions.isEmpty() && requests.isEmpty()) return
    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        permissions.forEach { request ->
            key(request.text("requestId")) {
                val id = request.text("requestId")
            var submitted by rememberSaveable(id, resetKey) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.text("title").ifBlank { "操作需要确认" }, style = MaterialTheme.typography.titleSmall)
                    Text(request.text("detail"), style = MaterialTheme.typography.bodySmall)
                    if (submitted) Text("已提交，正在同步处理结果…", style = MaterialTheme.typography.bodySmall)
                    (request["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { option ->
                        TextButton(enabled = enabled && !submitted && id.isNotBlank() && option.text("id").isNotBlank(), onClick = {
                            submitted = true
                            onPermission(id, option.text("id"))
                        }) { Text(option.text("label").ifBlank { option.text("id") }) }
                    }
                }
            }
        }
        }
        requests.forEach { request -> key(request.text("requestId")) { QuestionCard(request, enabled, resetKey, onAnswers) } }
    }
}

@Composable
private fun QuestionCard(request: JsonObject, enabled: Boolean, resetKey: Int, onAnswers: (String, JsonArray) -> Unit) {
    val requestId = request.text("requestId")
    val questions = (request["questions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    var answers by rememberSaveable(requestId) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var submitted by rememberSaveable(requestId, resetKey) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("需要你的补充", style = MaterialTheme.typography.titleSmall)
            questions.forEach { question ->
                val id = question.text("id")
                val multi = (question["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true
                val options = (question["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                var custom by rememberSaveable(requestId, id) { mutableStateOf("") }
                Text(question.text("header"), style = MaterialTheme.typography.labelLarge)
                Text(question.text("question"), style = MaterialTheme.typography.bodyMedium)
                options.forEach { option ->
                    val label = option.text("label")
                    val selected = label in answers[id].orEmpty()
                    fun choose() {
                        val current = answers[id].orEmpty()
                        answers = answers + (id to if (multi) {
                            if (selected) current - label else current + label
                        } else listOf(label))
                        if (!multi) custom = ""
                    }
                    Row {
                        if (multi) Checkbox(selected, onCheckedChange = { choose() }, enabled = enabled && !submitted)
                        else RadioButton(selected, onClick = { choose() }, enabled = enabled && !submitted)
                        Column(Modifier.padding(top = 10.dp)) {
                            Text(label)
                            option.text("description").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                OutlinedTextField(value = custom, onValueChange = { value ->
                    val previous = custom.trim()
                    custom = value
                    val values = if (multi) answers[id].orEmpty().filterNot { it == previous } else emptyList()
                    answers = answers + (id to (values + listOfNotNull(value.trim().takeIf(String::isNotBlank))))
                }, enabled = enabled && !submitted, label = { Text(if (options.isEmpty()) "你的回答" else "补充或自定义回答") },
                    modifier = Modifier.fillMaxWidth())
            }
            Button(enabled = enabled && !submitted && questions.isNotEmpty() && questions.all { it.text("id").isNotBlank() && answers[it.text("id")].orEmpty().isNotEmpty() }, onClick = {
                submitted = true
                onAnswers(requestId, JsonArray(questions.map { question -> buildJsonObject {
                    put("questionId", question.text("id"))
                    put("answers", JsonArray(answers[question.text("id")].orEmpty().map(::JsonPrimitive)))
                } }))
            }) { Text(if (submitted) "已提交，等待同步…" else "提交回答") }
        }
    }
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()