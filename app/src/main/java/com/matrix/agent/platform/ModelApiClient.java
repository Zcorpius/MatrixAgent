package com.matrix.agent.platform;

import android.util.Log;

import com.matrix.agent.core.agent.AgentMessage;
import com.matrix.agent.core.agent.FinishReason;
import com.matrix.agent.core.agent.ModelTurn;
import com.matrix.agent.core.agent.TaskPlan;
import com.matrix.agent.core.tool.ToolCall;
import com.matrix.agent.core.capability.ToolDefinition;
import com.matrix.agent.core.capability.ToolParameterDefinition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModelApiClient {
    private static final String TAG = "MatrixAgent";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    public String complete(ModelConfig config, String systemPrompt, String userPrompt) throws Exception {
        config.validate();
        Log.d(TAG, "[Http] complete protocol=" + config.protocol
                + " model=" + config.model + " systemChars=" + systemPrompt.length()
                + " userChars=" + userPrompt.length());
        switch (config.protocol) {
            case ANTHROPIC_MESSAGES:
                return callAnthropic(config, systemPrompt, userPrompt);
            case GEMINI_GENERATE_CONTENT:
                return callGemini(config, systemPrompt, userPrompt);
            case OLLAMA_CHAT:
                return callOllama(config, systemPrompt, userPrompt);
            case OPENAI_CHAT:
            default:
                return callOpenAiCompatible(config, systemPrompt, userPrompt);
        }
    }

    public TaskPlan planWithTools(ModelConfig config, String systemPrompt, String userPrompt,
            List<ToolDefinition> tools) throws Exception {
        config.validate();
        if (config.protocol != ApiProtocol.OPENAI_CHAT) {
            throw new IllegalArgumentException("原生 Tool Calling 当前仅支持 OpenAI-Compatible 协议");
        }
        Log.d(TAG, "[Http] planWithTools model=" + config.model
                + " tools=" + tools.size() + " userChars=" + userPrompt.length());
        JSONObject request = buildOpenAiToolRequest(config, systemPrompt, userPrompt, tools);
        JSONObject response = post(config.endpoint, request, "Authorization",
                config.apiKey.isEmpty() ? null : "Bearer " + config.apiKey, null, null);
        TaskPlan plan = parseOpenAiToolResponse(config, response, tools);
        Log.i(TAG, "[Http] planWithTools -> steps=" + plan.getSteps().size());
        return plan;
    }

    static JSONObject buildOpenAiToolRequest(ModelConfig config, String system, String user,
            List<ToolDefinition> tools) throws Exception {
        if (tools == null || tools.isEmpty()) throw new IllegalArgumentException("ToolDefinition 不能为空");
        JSONArray toolArray = new JSONArray();
        for (ToolDefinition tool : tools) toolArray.put(toOpenAiTool(tool));
        return new JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("temperature", 0.1)
                .put("messages", new JSONArray()
                        .put(message("system", system))
                        .put(message("user", user)))
                .put("tools", toolArray)
                .put("tool_choice", "auto");
    }

    static TaskPlan parseOpenAiToolResponse(ModelConfig config, JSONObject response,
            List<ToolDefinition> tools) throws Exception {
        JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONArray toolCalls = message.optJSONArray("tool_calls");
        if (toolCalls == null || toolCalls.length() == 0) {
            String finishReason = response.getJSONArray("choices").getJSONObject(0)
                    .optString("finish_reason", "(missing)");
            String content = message.optString("content", "");
            // 第七轮 P2-4:content 是模型 raw response,可能含回显的用户输入 / API Key。
            // body 字段也按 provider raw 治理。contentChars / respBytes 是元数据,保留。
            com.matrix.agent.core.agent.SafeLog.e(TAG, "[Http] no tool_calls in response. finish_reason="
                    + finishReason
                    + " contentChars=" + content.length()
                    + " contentHead=" + com.matrix.agent.core.agent.SafeLog.PROVIDER_RAW_PLACEHOLDER
                    + " respBytes=" + response.toString().length());
            throw new IllegalStateException("模型未返回 tool_calls；请确认模型支持原生 Tool Calling，或显式切换兼容模式");
        }
        Map<String, ToolDefinition> definitionsByModelName = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) definitionsByModelName.put(tool.getModelName(), tool);

        List<ToolCall> steps = new ArrayList<>();
        for (int index = 0; index < toolCalls.length(); index++) {
            JSONObject function = toolCalls.getJSONObject(index).getJSONObject("function");
            String modelName = function.getString("name");
            ToolDefinition definition = definitionsByModelName.get(modelName);
            if (definition == null) throw new IllegalStateException("模型返回未注册 Tool：" + modelName);
            Object rawArguments = function.opt("arguments");
            JSONObject arguments;
            if (rawArguments instanceof JSONObject) {
                arguments = (JSONObject) rawArguments;
            } else if (rawArguments instanceof String && !((String) rawArguments).trim().isEmpty()) {
                arguments = new JSONObject((String) rawArguments);
            } else {
                arguments = new JSONObject();
            }
            steps.add(new ToolCall(definition.getCapabilityName(), toMap(arguments)));
        }
        String content = message.optString("content", "").trim();
        String summary = "Native Tool Calling/" + config.displayName;
        if (!content.isEmpty() && !"null".equals(content)) summary += "：" + content;
        Log.d(TAG, "[Http] openai parse tool_calls=" + toolCalls.length()
                + " mapped=" + steps.size());
        return new TaskPlan(summary, steps);
    }

    /**
     * Anthropic 原生 Tool Calling。每轮把完整 conversation(含上轮 tool_use / tool_result)
     * 一次性发出,Anthropic 自行决定本轮还要不要再调 tool。返回 {@link ModelTurn} 供 Loop 拼回。
     */
    public ModelTurn callAnthropicWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        config.validate();
        if (config.protocol != ApiProtocol.ANTHROPIC_MESSAGES) {
            throw new IllegalArgumentException("Anthropic Native Tool Calling 仅支持 ANTHROPIC_MESSAGES 协议");
        }
        Log.i(TAG, "[Http] callAnthropicWithTools model=" + config.model
                + " conversationMsgs=" + conversation.size()
                + " tools=" + tools.size() + " systemChars=" + system.length());
        JSONObject request = buildAnthropicToolRequest(config, system, conversation, tools);
        JSONObject response = post(config.endpoint, request, "x-api-key", config.apiKey,
                "anthropic-version", "2023-06-01");
        ModelTurn turn = parseAnthropicToolResponse(response, tools);
        Log.d(TAG, "[Http] anthropic turn hasToolCalls=" + turn.hasToolCalls()
                + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
        return turn;
    }

    /**
     * OpenAI-Compatible 原生多轮 Tool Calling。每轮把完整 conversation(含 assistant.tool_calls
     * 与 role=tool 的 tool_call_id 关联)一次性发出,服务器自行决定本轮还要不要再调 tool。
     * 返回 {@link ModelTurn} 供 Loop 拼回。
     *
     * <p>与 Anthropic 路径({@link #callAnthropicWithTools})的差异:
     * <ul>
     *   <li>tool 结果用 role=tool 的独立 message,每个 tool_call 一条(Anthropic 是合并到
     *       单 user message 的多个 tool_result block);</li>
     *   <li>assistant.tool_calls[].id 必须原样回传到 tool.tool_call_id(模型生成,Runtime 透传,
     *       与 Anthropic tool_use.id 同样不允许 Runtime 自造);</li>
     *   <li>function.arguments 是 JSON 字符串,不是对象(部分本地服务器会返回对象,parse 路径做兼容);</li>
     *   <li>content 在有 tool_calls 时可以为 null/空(本地服务器更兼容空字符串)。</li>
     * </ul>
     */
    public ModelTurn callOpenAiWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        config.validate();
        if (config.protocol != ApiProtocol.OPENAI_CHAT) {
            throw new IllegalArgumentException(
                    "OpenAI Native Tool Calling 仅支持 OPENAI_CHAT 协议");
        }
        Log.i(TAG, "[Http] callOpenAiWithTools model=" + config.model
                + " conversationMsgs=" + conversation.size()
                + " tools=" + tools.size() + " systemChars=" + system.length());
        JSONObject request = buildOpenAiToolRequest(config, system, conversation, tools);
        JSONObject response = post(config.endpoint, request, "Authorization",
                config.apiKey.isEmpty() ? null : "Bearer " + config.apiKey, null, null);
        ModelTurn turn = parseOpenAiToolResponse(response, tools);
        Log.d(TAG, "[Http] openai-native turn hasToolCalls=" + turn.hasToolCalls()
                + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
        return turn;
    }

    /**
     * OpenAI 多轮 Tool Calling 请求体。conversation 列表序列化为 OpenAI messages 数组,
     * system 单独成一条 system message(不像 Anthropic 走 top-level system 字段)。
     */
    static JSONObject buildOpenAiToolRequest(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        if (tools == null || tools.isEmpty()) throw new IllegalArgumentException("ToolDefinition 不能为空");
        JSONArray toolArray = new JSONArray();
        for (ToolDefinition tool : tools) toolArray.put(toOpenAiTool(tool));
        java.util.Map<String, String> capabilityToModelName = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) capabilityToModelName.put(tool.getCapabilityName(), tool.getModelName());
        JSONArray messages = new JSONArray();
        messages.put(message("system", system));
        JSONArray conversationMessages = toOpenAiMessages(conversation, capabilityToModelName);
        for (int i = 0; i < conversationMessages.length(); i++) {
            messages.put(conversationMessages.getJSONObject(i));
        }
        return new JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("temperature", 0.1)
                .put("messages", messages)
                .put("tools", toolArray)
                .put("tool_choice", "auto");
    }

    /**
     * 解析 OpenAI 多轮响应,返回 {@link ModelTurn}。
     *
     * <p>无 tool_calls 时走 {@link ModelTurn#directAnswer(String)}(finishReason=STOP);
     * 有 tool_calls 时 {@link ModelTurn#ofToolCalls(List, String)},tool_calls[].id 原样保留
     * (供下一轮 tool message 关联 tool_call_id)。
     */
    static ModelTurn parseOpenAiToolResponse(JSONObject response,
            java.util.List<ToolDefinition> tools) throws Exception {
        JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONArray toolCalls = message.optJSONArray("tool_calls");
        String content = message.optString("content", "");
        String rawFinishReason = response.getJSONArray("choices").getJSONObject(0)
                .optString("finish_reason", "");
        // 第五轮 P1-4:LENGTH 必须在解析 tool_calls 之前判定——max_tokens 截断发生在生成过程中,
        // 即使部分 tool_calls 已出现也无法保证 arguments JSON 完整。执行截断的 tool_calls 会
        // 让模型基于半截 destination / 半截 value 操作,极其危险。
        if (mapOpenAiFinishReason(rawFinishReason) == FinishReason.LENGTH) {
            Log.w(TAG, "[Http] openai-native LENGTH short-circuit finish_reason=" + rawFinishReason
                    + " toolCallsPresent=" + (toolCalls != null && toolCalls.length() > 0)
                    + " toolCallCount=" + (toolCalls == null ? 0 : toolCalls.length())
                    + " contentChars=" + content.length()
                    + " — 拒绝执行可能被截断的 tool_calls");
            return ModelTurn.of(content, FinishReason.LENGTH);
        }
        if (toolCalls == null || toolCalls.length() == 0) {
            // 第四轮 P2-2:无 tool_call 时按 finish_reason 决定 FinishReason,
            // 不能一律 directAnswer(STOP)——LENGTH 截断会被错判为正常完成。
            FinishReason mapped = mapOpenAiFinishReason(rawFinishReason);
            // 第五轮 P2-1:STOP + 空 content → 协议不一致,降级为 NONE。
            // ModelTurn.of(STOP, "") 会抛异常,这里在抛之前先清洗。
            if (mapped == FinishReason.STOP && (content == null || content.trim().isEmpty())) {
                Log.w(TAG, "[Http] openai-native finish_reason=stop but content empty -> NONE");
                mapped = FinishReason.NONE;
            }
            Log.d(TAG, "[Http] openai-native directAnswer finish_reason=" + rawFinishReason
                    + " mapped=" + mapped + " contentChars=" + content.length());
            return ModelTurn.of(content, mapped);
        }
        java.util.Map<String, ToolDefinition> definitionsByModelName = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) definitionsByModelName.put(tool.getModelName(), tool);

        // 第七轮 P2-1:ToolCall 与 finish_reason 一致性校验。
        // OpenAI 协议规定:返回 tool_calls 时 finish_reason 必须为 "tool_calls"。
        // 不一致组合(finish_reason=stop/missing/unknown + tool_calls)→ PROTOCOL_ERROR,
        // 不得执行工具——可能来自不规范的本地 OpenAI-Compatible 服务或被篡改响应,
        // 让核心解析器无条件放宽会把模型幻觉 / 协议错误掩盖成正常 tool 执行。
        FinishReason mappedFinish = mapOpenAiFinishReason(rawFinishReason);
        if (mappedFinish != FinishReason.TOOL_CALLS) {
            Log.w(TAG, "[Http] openai-native PROTOCOL_ERROR: tool_calls present but finish_reason="
                    + rawFinishReason + " (expected tool_calls) - skipping tool execution");
            return ModelTurn.of(content, FinishReason.NONE);
        }

        java.util.List<ToolCall> calls = new ArrayList<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (int index = 0; index < toolCalls.length(); index++) {
            JSONObject toolCall = toolCalls.getJSONObject(index);
            // 第四轮 P2-1:缺失/空 ID 不能静默补 UUID,必须显式失败
            String id = toolCall.optString("id", "");
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalStateException(
                        "OpenAI 响应缺失 tool_calls[" + index + "].id,Provider 协议不一致");
            }
            if (!seenIds.add(id)) {
                throw new IllegalStateException(
                        "OpenAI 响应中 tool_calls 出现重复 id=" + id + ",Provider 协议不一致");
            }
            JSONObject function = toolCall.getJSONObject("function");
            String modelName = function.getString("name");
            ToolDefinition definition = definitionsByModelName.get(modelName);
            if (definition == null) {
                throw new IllegalStateException("OpenAI 返回未注册 Tool:" + modelName);
            }
            Object rawArguments = function.opt("arguments");
            java.util.Map<String, Object> arguments;
            if (rawArguments instanceof JSONObject) {
                arguments = toMap((JSONObject) rawArguments);
            } else if (rawArguments instanceof String && !((String) rawArguments).trim().isEmpty()) {
                arguments = toMap(new JSONObject((String) rawArguments));
            } else {
                arguments = new LinkedHashMap<>();
            }
            calls.add(ToolCall.withId(id, definition.getCapabilityName(), arguments));
        }
        Log.d(TAG, "[Http] openai-native parse tool_calls=" + toolCalls.length()
                + " mapped=" + calls.size());
        return ModelTurn.ofToolCalls(calls, content);
    }

    /**
     * 把 Agent Loop 的 conversation 序列化为 OpenAI messages 数组。
     *
     * <p>规则:
     * <ul>
     *   <li>SYSTEM / USER → {role, content};</li>
     *   <li>ASSISTANT → {role:"assistant", content(可空), tool_calls?};
     *       每个 tool_call 序列化为 {id, type:"function", function:{name, arguments(JSON string)}};</li>
     *   <li>TOOL → {role:"tool", content, tool_call_id, name?}。
     *       OpenAI 协议要求每个 tool_call 一条独立 tool message(不像 Anthropic 合并到同一 user)。</li>
     * </ul>
     */
    private static JSONArray toOpenAiMessages(java.util.List<AgentMessage> conversation,
            java.util.Map<String, String> capabilityToModelName) throws Exception {
        JSONArray messages = new JSONArray();
        for (AgentMessage msg : conversation) {
            switch (msg.getRole()) {
                case SYSTEM:
                    // system 已在 buildOpenAiToolRequest 单独加入,这里跳过避免重复
                    continue;
                case USER:
                    messages.put(new JSONObject()
                            .put("role", "user")
                            .put("content", msg.getContent()));
                    break;
                case ASSISTANT: {
                    JSONObject assistant = new JSONObject().put("role", "assistant");
                    String content = msg.getContent();
                    assistant.put("content", content == null ? "" : content);
                    if (!msg.getToolCalls().isEmpty()) {
                        JSONArray toolCalls = new JSONArray();
                        for (ToolCall call : msg.getToolCalls()) {
                            String modelName = capabilityToModelName.get(call.getCapabilityName());
                            if (modelName == null) modelName = toModelToolName(call.getCapabilityName());
                            JSONObject function = new JSONObject()
                                    .put("name", modelName)
                                    .put("arguments", toJson(call.getArguments()).toString());
                            toolCalls.put(new JSONObject()
                                    .put("id", call.getStepId())
                                    .put("type", "function")
                                    .put("function", function));
                        }
                        assistant.put("tool_calls", toolCalls);
                    }
                    messages.put(assistant);
                    break;
                }
                case TOOL: {
                    JSONObject tool = new JSONObject()
                            .put("role", "tool")
                            .put("content", msg.getContent())
                            .put("tool_call_id", msg.getToolCallId());
                    if (msg.getToolName() != null && !msg.getToolName().isEmpty()) {
                        tool.put("name", toModelToolName(msg.getToolName()));
                    }
                    messages.put(tool);
                    break;
                }
            }
        }
        return messages;
    }

    static JSONObject buildAnthropicToolRequest(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        if (tools == null || tools.isEmpty()) throw new IllegalArgumentException("ToolDefinition 不能为空");
        JSONArray toolArray = new JSONArray();
        for (ToolDefinition tool : tools) toolArray.put(toAnthropicTool(tool));
        return new JSONObject()
                .put("model", config.model)
                .put("max_tokens", 2048)
                .put("temperature", 0.1)
                .put("stream", false)
                .put("system", system)
                .put("messages", toAnthropicMessages(conversation))
                .put("tools", toolArray);
    }

    static ModelTurn parseAnthropicToolResponse(JSONObject response, java.util.List<ToolDefinition> tools)
            throws Exception {
        JSONArray content = response.getJSONArray("content");
        StringBuilder text = new StringBuilder();
        java.util.List<ToolCall> calls = new java.util.ArrayList<>();
        java.util.Map<String, ToolDefinition> byModelName = new java.util.LinkedHashMap<>();
        for (ToolDefinition tool : tools) byModelName.put(tool.getModelName(), tool);

        int textBlocks = 0;
        int toolUseBlocks = 0;
        java.util.Set<String> seenToolUseIds = new java.util.HashSet<>();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.getJSONObject(i);
            String type = block.getString("type");
            if ("text".equals(type)) {
                textBlocks++;
                text.append(block.optString("text", ""));
            } else if ("tool_use".equals(type)) {
                toolUseBlocks++;
                // 第四轮 P2-1:缺失/空 ID 不能静默补 UUID,必须显式失败
                String id = block.optString("id", "");
                if (id == null || id.trim().isEmpty()) {
                    throw new IllegalStateException(
                            "Anthropic 响应第 " + i + " 个 tool_use block 缺失 id");
                }
                if (!seenToolUseIds.add(id)) {
                    throw new IllegalStateException(
                            "Anthropic 响应中 tool_use 出现重复 id=" + id);
                }
                String modelName = block.getString("name");
                ToolDefinition definition = byModelName.get(modelName);
                if (definition == null) {
                    throw new IllegalStateException("Anthropic 返回未注册 Tool：" + modelName);
                }
                Object rawInput = block.opt("input");
                java.util.Map<String, Object> input = rawInput instanceof JSONObject
                        ? toMap((JSONObject) rawInput)
                        : new java.util.LinkedHashMap<>();
                calls.add(ToolCall.withId(id, definition.getCapabilityName(), input));
            }
        }
        Log.d(TAG, "[Http] anthropic parse contentBlocks=" + content.length()
                + " text=" + textBlocks + " tool_use=" + toolUseBlocks
                + " textChars=" + text.length());
        String rawStopReason = response.optString("stop_reason", "");
        // 第五轮 P1-4:LENGTH 必须在返回 ofToolCalls 之前判定——max_tokens 截断发生在生成过程中,
        // 即使部分 tool_use block 已出现也无法保证 input JSON 完整。执行截断的 tool_calls 会
        // 让模型基于半截 destination / 半截 value 操作,极其危险。
        if (mapAnthropicStopReason(rawStopReason) == FinishReason.LENGTH) {
            Log.w(TAG, "[Http] anthropic LENGTH short-circuit stop_reason=" + rawStopReason
                    + " toolUseBlocks=" + toolUseBlocks
                    + " textChars=" + text.length()
                    + " — 拒绝执行可能被截断的 tool_use blocks");
            return ModelTurn.of(text.toString(), FinishReason.LENGTH);
        }
        if (calls.isEmpty()) {
            // 第四轮 P2-2:无 tool_use 时按 stop_reason 决定 FinishReason,
            // max_tokens 截断不能被错判为正常完成。
            FinishReason mapped = mapAnthropicStopReason(rawStopReason);
            String textStr = text.toString();
            // 第五轮 P2-1:STOP + 空 content → 协议不一致,降级为 NONE。
            if (mapped == FinishReason.STOP && textStr.trim().isEmpty()) {
                Log.w(TAG, "[Http] anthropic stop_reason=end_turn but content empty -> NONE");
                mapped = FinishReason.NONE;
            }
            Log.d(TAG, "[Http] anthropic directAnswer stop_reason=" + rawStopReason
                    + " mapped=" + mapped);
            return ModelTurn.of(textStr, mapped);
        }
        // 第七轮 P2-1:tool_use 与 stop_reason 一致性校验。
        // Anthropic 协议规定:返回 tool_use 时 stop_reason 必须为 "tool_use"。
        // 不一致组合(stop_reason=end_turn/missing/unknown + tool_use blocks)→ PROTOCOL_ERROR,
        // 不得执行工具。
        FinishReason mappedStop = mapAnthropicStopReason(rawStopReason);
        if (mappedStop != FinishReason.TOOL_CALLS) {
            Log.w(TAG, "[Http] anthropic PROTOCOL_ERROR: tool_use present but stop_reason="
                    + rawStopReason + " (expected tool_use) - skipping tool execution");
            return ModelTurn.of(text.toString(), FinishReason.NONE);
        }
        return ModelTurn.ofToolCalls(calls, text.toString());
    }

    private static JSONArray toAnthropicMessages(java.util.List<AgentMessage> conversation)
            throws Exception {
        JSONArray messages = new JSONArray();
        JSONObject pendingToolResults = null;
        for (AgentMessage msg : conversation) {
            switch (msg.getRole()) {
                case SYSTEM:
                    continue;
                case USER:
                    if (pendingToolResults != null) {
                        messages.put(pendingToolResults);
                        pendingToolResults = null;
                    }
                    messages.put(new JSONObject().put("role", "user").put("content", msg.getContent()));
                    break;
                case ASSISTANT:
                    if (pendingToolResults != null) {
                        messages.put(pendingToolResults);
                        pendingToolResults = null;
                    }
                    messages.put(new JSONObject().put("role", "assistant")
                            .put("content", assistantContent(msg)));
                    break;
                case TOOL:
                    if (pendingToolResults == null) {
                        pendingToolResults = new JSONObject().put("role", "user");
                    }
                    JSONArray content = pendingToolResults.optJSONArray("content");
                    if (content == null) content = new JSONArray();
                    content.put(new JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", msg.getToolCallId())
                            .put("content", msg.getContent()));
                    pendingToolResults.put("content", content);
                    break;
            }
        }
        if (pendingToolResults != null) messages.put(pendingToolResults);
        return messages;
    }

    private static JSONArray assistantContent(AgentMessage msg) throws Exception {
        JSONArray content = new JSONArray();
        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            content.put(new JSONObject().put("type", "text").put("text", msg.getContent()));
        }
        for (ToolCall call : msg.getToolCalls()) {
            content.put(new JSONObject()
                    .put("type", "tool_use")
                    .put("id", call.getStepId())
                    .put("name", toModelToolName(call.getCapabilityName()))
                    .put("input", toJson(call.getArguments())));
        }
        if (content.length() == 0) {
            content.put(new JSONObject().put("type", "text").put("text", ""));
        }
        return content;
    }

    private static JSONObject toAnthropicTool(ToolDefinition tool) throws Exception {
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (ToolParameterDefinition parameter : tool.getParameters()) {
            JSONObject schema = new JSONObject()
                    .put("type", jsonType(parameter.getType()))
                    .put("description", parameter.getDescription());
            if (!parameter.getEnumValues().isEmpty()) {
                JSONArray values = new JSONArray();
                for (String value : parameter.getEnumValues()) values.put(value);
                schema.put("enum", values);
            }
            if (parameter.getMinimum() != null) schema.put("minimum", parameter.getMinimum());
            if (parameter.getMaximum() != null) schema.put("maximum", parameter.getMaximum());
            properties.put(parameter.getName(), schema);
            if (parameter.isRequired()) required.put(parameter.getName());
        }
        JSONObject inputSchema = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", required)
                .put("additionalProperties", false);
        return new JSONObject()
                .put("name", tool.getModelName())
                .put("description", tool.getDescription())
                .put("input_schema", inputSchema);
    }

    private static String toModelToolName(String capabilityName) {
        return capabilityName.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static JSONObject toJson(java.util.Map<String, Object> map) throws Exception {
        JSONObject obj = new JSONObject();
        if (map == null) return obj;
        for (java.util.Map.Entry<String, Object> entry : map.entrySet()) {
            obj.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
        }
        return obj;
    }

    private static JSONObject toOpenAiTool(ToolDefinition tool) throws Exception {
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (ToolParameterDefinition parameter : tool.getParameters()) {
            JSONObject schema = new JSONObject()
                    .put("type", jsonType(parameter.getType()))
                    .put("description", parameter.getDescription());
            if (!parameter.getEnumValues().isEmpty()) {
                JSONArray values = new JSONArray();
                for (String value : parameter.getEnumValues()) values.put(value);
                schema.put("enum", values);
            }
            if (parameter.getMinimum() != null) schema.put("minimum", parameter.getMinimum());
            if (parameter.getMaximum() != null) schema.put("maximum", parameter.getMaximum());
            properties.put(parameter.getName(), schema);
            if (parameter.isRequired()) required.put(parameter.getName());
        }
        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", required)
                .put("additionalProperties", false);
        return new JSONObject().put("type", "function")
                .put("function", new JSONObject()
                        .put("name", tool.getModelName())
                        .put("description", tool.getDescription())
                        .put("parameters", parameters));
    }

    private static String jsonType(ToolParameterDefinition.Type type) {
        switch (type) {
            case INTEGER: return "integer";
            case NUMBER: return "number";
            case BOOLEAN: return "boolean";
            case STRING:
            default: return "string";
        }
    }

    private static Map<String, Object> toMap(JSONObject object) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.get(key);
            map.put(key, value == JSONObject.NULL ? null : value);
        }
        return map;
    }

    private String callOpenAiCompatible(ModelConfig config, String system, String user) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("temperature", 0.1)
                .put("messages", new JSONArray()
                        .put(message("system", system))
                        .put(message("user", user)));
        JSONObject response = post(config.endpoint, body, "Authorization",
                config.apiKey.isEmpty() ? null : "Bearer " + config.apiKey, null, null);
        return response.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "");
    }

    private String callAnthropic(ModelConfig config, String system, String user) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("max_tokens", 2048)
                .put("system", system)
                .put("messages", new JSONArray().put(message("user", user)));
        JSONObject response = post(config.endpoint, body, "x-api-key", config.apiKey,
                "anthropic-version", "2023-06-01");
        return response.getJSONArray("content").getJSONObject(0).optString("text", "");
    }

    private String callGemini(ModelConfig config, String system, String user) throws Exception {
        String endpoint = config.endpoint.replace("{model}", config.model);
        JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", user)))))
                .put("generationConfig", new JSONObject().put("temperature", 0.1));
        JSONObject response = post(endpoint, body, "x-goog-api-key", config.apiKey, null, null);
        return response.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .optString("text", "");
    }

    private String callOllama(ModelConfig config, String system, String user) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("messages", new JSONArray()
                        .put(message("system", system))
                        .put(message("user", user)));
        JSONObject response = post(config.endpoint, body, null, null, null, null);
        return response.getJSONObject("message").optString("content", "");
    }

    private static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static JSONObject post(String endpoint, JSONObject body,
            String header1, String value1, String header2, String value2) throws Exception {
        long started = System.nanoTime();
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        boolean hasAuth = (header1 != null && value1 != null && !value1.isEmpty())
                || (header2 != null && value2 != null && !value2.isEmpty());
        Log.d(TAG, "[Http] POST " + maskEndpoint(endpoint)
                + " payloadBytes=" + payload.length
                + " auth=" + (hasAuth ? "yes" : "no"));
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (header1 != null && value1 != null && !value1.isEmpty()) connection.setRequestProperty(header1, value1);
            if (header2 != null && value2 != null && !value2.isEmpty()) connection.setRequestProperty(header2, value2);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "[Http] worker thread interrupted before response");
                throw new InterruptedException("模型请求已取消");
            }
            int code = connection.getResponseCode();
            InputStream input = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(input);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            Log.d(TAG, "[Http] <- HTTP " + code + " respBytes=" + response.length()
                    + " costMs=" + elapsedMs);
            if (code < 200 || code >= 300) {
                // 第七轮 P2-4:HTTP 错误响应可能回显请求参数(API Key、目的地等),
                // 或含厂商网关诊断信息。整段用占位符包裹,绝不原文进 logcat / exception message。
                com.matrix.agent.core.agent.SafeLog.wProviderRaw(TAG, "[Http] HTTP error ",
                        code, truncate(response, 500));
                throw new IllegalStateException("HTTP " + code
                        + ": body=" + com.matrix.agent.core.agent.SafeLog.PROVIDER_RAW_PLACEHOLDER);
            }
            return new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private static String maskEndpoint(String endpoint) {
        if (endpoint == null) return "";
        int query = endpoint.indexOf('?');
        return query >= 0 ? endpoint.substring(0, query) : endpoint;
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /**
     * 把 OpenAI finish_reason 字符串映射为 {@link FinishReason}。
     *
     * <p>第四轮 P2-2:LENGTH 必须独立识别——模型输出因 max_tokens 截断时,
     * content 可能不完整,AgentEngine 必须据此走异常终止,不能 SUCCEEDED。
     *
     * <ul>
     *   <li>"stop" → STOP(正常完成)</li>
     *   <li>"length" → LENGTH(截断,Observation 不完整)</li>
     *   <li>"content_filter" / "function_call"(legacy) / 其他厂商扩展 / 缺失 → NONE</li>
     *   <li>"tool_calls" 留给 caller 单独分支处理,这里返回 TOOL_CALLS 仅作记录</li>
     * </ul>
     */
    private static FinishReason mapOpenAiFinishReason(String raw) {
        if (raw == null || raw.isEmpty()) return FinishReason.NONE;
        switch (raw) {
            case "stop": return FinishReason.STOP;
            case "length": return FinishReason.LENGTH;
            case "tool_calls": return FinishReason.TOOL_CALLS;
            default: return FinishReason.NONE;
        }
    }

    /**
     * 把 Anthropic stop_reason 字符串映射为 {@link FinishReason}。
     *
     * <ul>
     *   <li>"end_turn" / "stop_sequence" → STOP</li>
     *   <li>"max_tokens" → LENGTH</li>
     *   <li>"tool_use" → TOOL_CALLS</li>
     *   <li>其他 / 缺失 → NONE</li>
     * </ul>
     */
    private static FinishReason mapAnthropicStopReason(String raw) {
        if (raw == null || raw.isEmpty()) return FinishReason.NONE;
        switch (raw) {
            case "end_turn":
            case "stop_sequence":
                return FinishReason.STOP;
            case "max_tokens":
                return FinishReason.LENGTH;
            case "tool_use":
                return FinishReason.TOOL_CALLS;
            default:
                return FinishReason.NONE;
        }
    }
}
