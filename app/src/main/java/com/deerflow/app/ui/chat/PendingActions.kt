package com.deerflow.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deerflow.app.data.waku.ServiceCredentialForm
import com.deerflow.app.ui.theme.deerflowColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PendingActions(
    permissions: List<JsonObject>,
    requests: List<JsonObject>,
    enabled: Boolean,
    resetKey: Int,
    secureCredentialTransport: Boolean,
    onCredential: (String, JsonObject) -> Unit,
    onPermission: (String, String) -> Unit,
    onAnswers: (String, JsonArray) -> Unit,
) {
    if (permissions.isEmpty() && requests.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.deerflowColors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        permissions.forEach { request ->
            key(request.text("requestId")) {
                val id = request.text("requestId")
                val options = (request["options"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val allow = options.singleOrNull { it.text("id") == "allow_once" }
                val requiresForm = allow?.containsKey("credentialForm") == true
                val form = allow?.let(ServiceCredentialForm::fromOption)
                var submitted by rememberSaveable(id, form?.publicKey, resetKey) { mutableStateOf(false) }
                var editing by remember(id, form?.publicKey, enabled, secureCredentialTransport) { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, semantic.warning.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(semantic.warningContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (requiresForm) Icons.Outlined.Lock else Icons.Outlined.Security,
                                    contentDescription = null,
                                    tint = semantic.warning,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = request.text("title").ifBlank { "操作需要确认" },
                                style = MaterialTheme.typography.titleSmall,
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        request.text("detail").takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }

                        if (requiresForm && form == null) {
                            Surface(
                                color = scheme.errorContainer,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    text = "此凭据表单需要更新客户端。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        } else if (requiresForm && !secureCredentialTransport) {
                            Surface(
                                color = semantic.warningContainer,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    text = "手机录入凭据需要 WSS 安全连接或本机回环连接，请检查连接设置。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = semantic.onWarningContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }

                        if (submitted) {
                            Text(
                                text = "已提交，正在同步处理结果…",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.primary,
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            options.forEach { option ->
                                val optionId = option.text("id")
                                val enter = optionId == "allow_once" && requiresForm
                                val label = if (enter) "填写服务凭据" else option.text("label").ifBlank { optionId }
                                val canClick = enabled && !submitted && id.isNotBlank() && optionId.isNotBlank() &&
                                    (!enter || (form != null && secureCredentialTransport))
                                val onOptionClick = {
                                    if (enter) {
                                        editing = true
                                    } else {
                                        submitted = true
                                        onPermission(id, optionId)
                                    }
                                }
                                when {
                                    enter || optionId.startsWith("allow") -> {
                                        Button(
                                            onClick = onOptionClick,
                                            enabled = canClick,
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Text(label)
                                        }
                                    }
                                    optionId.startsWith("reject") || optionId.startsWith("deny") || optionId == "cancel" -> {
                                        OutlinedButton(
                                            onClick = onOptionClick,
                                            enabled = canClick,
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                                            border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.45f)),
                                        ) {
                                            Text(label)
                                        }
                                    }
                                    else -> {
                                        FilledTonalButton(
                                            onClick = onOptionClick,
                                            enabled = canClick,
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Text(label)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (editing && form != null && enabled && !submitted && secureCredentialTransport) {
                    CredentialEnrollmentDialog(
                        form = form,
                        onBackground = { editing = false },
                        onCancel = {
                            editing = false
                            submitted = true
                            onPermission(id, "reject_once")
                        },
                        onSealed = { sealed ->
                            editing = false
                            submitted = true
                            onCredential(id, sealed)
                        },
                    )
                }
            }
        }
        requests.forEach { request ->
            key(request.text("requestId")) {
                QuestionCard(request, enabled, resetKey, onAnswers)
            }
        }
    }
}

@Composable
private fun QuestionCard(
    request: JsonObject,
    enabled: Boolean,
    resetKey: Int,
    onAnswers: (String, JsonArray) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val requestId = request.text("requestId")
    val questions = (request["questions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    var answers by rememberSaveable(requestId) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var submitted by rememberSaveable(requestId, resetKey) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = "需要你的补充",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
            }

            questions.forEach { question ->
                val id = question.text("id")
                val multi = (question["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true
                val options = (question["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                var custom by rememberSaveable(requestId, id) { mutableStateOf("") }

                question.text("header").takeIf { it.isNotBlank() }?.let { header ->
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                    )
                }
                Text(
                    text = question.text("question"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )

                options.forEach { option ->
                    val label = option.text("label")
                    val selected = label in answers[id].orEmpty()
                    fun choose() {
                        val current = answers[id].orEmpty()
                        answers = answers + (
                            id to if (multi) {
                                if (selected) current - label else current + label
                            } else {
                                listOf(label)
                            }
                            )
                        if (!multi) custom = ""
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = enabled && !submitted) { choose() },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainer,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) scheme.primary.copy(alpha = 0.65f) else scheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (multi) {
                                Checkbox(selected, onCheckedChange = { choose() }, enabled = enabled && !submitted)
                            } else {
                                RadioButton(selected, onClick = { choose() }, enabled = enabled && !submitted)
                            }
                            Spacer(Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                )
                                option.text("description").takeIf(String::isNotBlank)?.let { desc ->
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = custom,
                    onValueChange = { value ->
                        val previous = custom.trim()
                        custom = value
                        val values = if (multi) answers[id].orEmpty().filterNot { it == previous } else emptyList()
                        answers = answers + (id to (values + listOfNotNull(value.trim().takeIf(String::isNotBlank))))
                    },
                    enabled = enabled && !submitted,
                    label = { Text(if (options.isEmpty()) "你的回答" else "补充或自定义回答") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = enabled && !submitted && questions.isNotEmpty() &&
                    questions.all { it.text("id").isNotBlank() && answers[it.text("id")].orEmpty().isNotEmpty() },
                onClick = {
                    submitted = true
                    onAnswers(
                        requestId,
                        JsonArray(
                            questions.map { question ->
                                buildJsonObject {
                                    put("questionId", question.text("id"))
                                    put("answers", JsonArray(answers[question.text("id")].orEmpty().map(::JsonPrimitive)))
                                }
                            },
                        ),
                    )
                },
            ) {
                Text(if (submitted) "已提交，等待同步…" else "提交回答")
            }
        }
    }
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
