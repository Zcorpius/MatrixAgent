package com.matrix.agent.platform;

import android.util.Log;

import com.matrix.agent.core.identity.AgentRequest;
import com.matrix.agent.core.identity.Actor;
import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.agent.Planner;
import com.matrix.agent.core.memory.MemoryStore;
import com.matrix.agent.core.session.SessionContext;
import com.matrix.agent.core.agent.TaskPlan;
import com.matrix.agent.core.tool.ToolCall;
import com.matrix.agent.core.capability.ToolDefinition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class LlmPlanner implements Planner {
    private static final String TAG = "MatrixAgent";
    private static final String PROMPT_PREFIX =
            "你是车机任务规划器。只输出一个 JSON 对象，不要 Markdown。"
            + "格式：{\"summary\":\"...\",\"steps\":[{\"capability\":\"...\",\"arguments\":{}}]}。"
            + "只能使用下面注册的能力，不允许创造能力名。最多8步。\n";

    private final ModelApiClient client;
    private final ModelConfig config;
    private final String systemPrompt;
    private final List<ToolDefinition> toolDefinitions;
    private final MemoryStore memoryStore;

    public LlmPlanner(ModelApiClient client, ModelConfig config, CapabilityRegistry registry) {
        this(client, config, registry, null);
    }

    public LlmPlanner(ModelApiClient client, ModelConfig config, CapabilityRegistry registry,
            MemoryStore memoryStore) {
        this.client = client;
        this.config = config;
        this.systemPrompt = PROMPT_PREFIX + registry.toPlannerInstructions();
        this.toolDefinitions = registry.toToolDefinitions();
        this.memoryStore = memoryStore;
    }

    @Override
    public TaskPlan plan(AgentRequest request, SessionContext context) {
        try {
            String userPrompt = "发起者=" + request.getActor()
                    + "\n最近上下文=" + context.getRecentTurns()
                    + "\n已保存的偏好 key 列表=" + savedKeysFor(userId(request))
                    + "\n用户请求=" + request.getText();
            if (config.plannerMode == PlannerMode.NATIVE_TOOL_CALLING) {
                return client.planWithTools(config, systemPrompt, userPrompt, toolDefinitions);
            }
            String raw = client.complete(config, systemPrompt, userPrompt);
            JSONObject root = new JSONObject(cleanJson(raw));
            JSONArray array = root.getJSONArray("steps");
            List<ToolCall> steps = new ArrayList<>();
            for (int i = 0; i < array.length() && i < 8; i++) {
                JSONObject step = array.getJSONObject(i);
                steps.add(new ToolCall(step.getString("capability"),
                        toMap(step.optJSONObject("arguments"))));
            }
            return new TaskPlan("LLM/" + config.displayName + "："
                    + root.optString("summary", "结构化规划"), steps);
        } catch (Exception error) {
            throw new IllegalStateException("模型规划失败：" + safeMessage(error), error);
        }
    }

    private static Map<String, Object> toMap(JSONObject object) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        if (object == null) return map;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.get(key);
            map.put(key, value == JSONObject.NULL ? null : value);
        }
        return map;
    }

    /** 与 MockCapabilityProvider 一致:driver → demo-driver,passenger → demo-passenger。 */
    private static String userId(AgentRequest request) {
        return request.getActor() == Actor.DRIVER ? "demo-driver" : "demo-passenger";
    }

    /**
     * 列出该用户已保存的所有偏好 key(只 key,不含 value——避免敏感数据进 prompt)。
     * 注入到 prompt 后,模型查询 memory.preference.get 时直接用现有 key,不再脑补命名。
     */
    private String savedKeysFor(String userId) {
        if (memoryStore == null) return "（MemoryStore 未注入,无历史 key）";
        try {
            Map<String, String> all = memoryStore.getAllPreferences(userId);
            if (all == null || all.isEmpty()) {
                Log.d(TAG, "[LlmPlanner] savedKeys user=" + userId + " -> empty");
                return "（无）";
            }
            Set<String> sorted = new TreeSet<>(all.keySet());
            String joined = String.join(", ", sorted);
            Log.d(TAG, "[LlmPlanner] savedKeys user=" + userId
                    + " count=" + sorted.size() + " keys=" + joined);
            return "查询时必须使用这些精确字符串之一:[" + joined + "]";
        } catch (Exception error) {
            Log.w(TAG, "[LlmPlanner] savedKeys lookup failed: " + error.getMessage());
            return "（读取失败）";
        }
    }

    private static String cleanJson(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                value = value.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }
}
