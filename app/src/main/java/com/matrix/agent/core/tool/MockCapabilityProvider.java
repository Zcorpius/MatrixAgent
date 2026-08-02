package com.matrix.agent.core.tool;

import android.util.Log;

import com.matrix.agent.core.memory.*;
import com.matrix.agent.core.identity.*;
import com.matrix.agent.core.capability.*;


import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stateful AAOS replacement used by the emulator build. */
public final class MockCapabilityProvider implements CapabilityProvider {
    private static final String TAG = "MatrixAgent";
    private final MemoryStore memoryStore;
    private final Map<String, Object> commandedState = new ConcurrentHashMap<>();
    private final Map<String, Object> observedState = new ConcurrentHashMap<>();
    private final AtomicBoolean failNextVehicleReadback = new AtomicBoolean();

    public MockCapabilityProvider(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        observedState.put("driver.temperature", 22);
        observedState.put("passenger.temperature", 22);
        observedState.put("driver.seatHeating", 0);
        observedState.put("passenger.seatHeating", 0);
        observedState.put("battery.percent", 78);
        observedState.put("tire.frontLeft.kPa", 240);
        observedState.put("tire.frontRight.kPa", 241);
        observedState.put("tire.rearLeft.kPa", 238);
        observedState.put("tire.rearRight.kPa", 239);
        observedState.put("navigation.destination", "无");
        Log.i(TAG, "[Provider] init mock state observedKeys=" + observedState.size());
    }

    @Override
    public ToolResult execute(AgentRequest request, ToolCall call) {
        long started = System.nanoTime();
        String capability = call.getCapabilityName();
        Map<String, Object> readback = new LinkedHashMap<>();
        Log.d(TAG, "[Provider] execute cap=" + capability
                + " args=" + call.getArguments());

        if ("vehicle.climate.set_temperature".equals(capability)) {
            VehicleZone zone = VehicleZone.parse(call.argument("zone"));
            int temperature = ((Number) call.argument("temperature")).intValue();
            String key = zone.wireValue() + ".temperature";
            commandAndApply(key, temperature);
            readback.put(key, observedState.get(key));
            boolean verified = Integer.valueOf(temperature).equals(observedState.get(key));
            Log.d(TAG, "[Provider] climate key=" + key + " value=" + temperature
                    + " readback=" + observedState.get(key) + " verified=" + verified);
            return result(capability, "空调温度已设置", readback, verified, started);
        }

        if ("vehicle.seat.set_heating_level".equals(capability)) {
            VehicleZone zone = VehicleZone.parse(call.argument("zone"));
            int level = ((Number) call.argument("level")).intValue();
            String key = zone.wireValue() + ".seatHeating";
            commandAndApply(key, level);
            readback.put(key, observedState.get(key));
            boolean verified = Integer.valueOf(level).equals(observedState.get(key));
            Log.d(TAG, "[Provider] seat key=" + key + " value=" + level
                    + " verified=" + verified);
            return result(capability, "座椅加热等级已设置", readback, verified, started);
        }

        if ("vehicle.info.get_battery".equals(capability)) {
            readback.put("battery.percent", observedState.get("battery.percent"));
            return result(capability, "当前电量 78%", readback, true, started);
        }

        if ("vehicle.info.get_tire_pressure".equals(capability)) {
            for (Map.Entry<String, Object> entry : observedState.entrySet()) {
                if (entry.getKey().startsWith("tire.")) {
                    readback.put(entry.getKey(), entry.getValue());
                }
            }
            return result(capability, "四轮胎压状态正常", readback, true, started);
        }

        if ("navigation.start_route".equals(capability)) {
            String destination = String.valueOf(call.argument("destination"));
            if ("失败测试点".equals(destination)) {
                Log.w(TAG, "[Provider] navigation -> EXECUTION_FAILED (mock 失败测试点)");
                return new ToolResult(
                        ToolResult.Status.EXECUTION_FAILED,
                        capability,
                        "Mock 导航服务不可用",
                        Collections.emptyMap(),
                        false,
                        elapsedMillis(started));
            }
            commandAndApply("navigation.destination", destination);
            readback.put("navigation.destination", observedState.get("navigation.destination"));
            boolean verified = destination.equals(observedState.get("navigation.destination"));
            // 第七轮 P2-4:导航 destination 是业务敏感字段,绝不原文进 logcat。
            Log.d(TAG, "[Provider] navigation dest="
                    + com.matrix.agent.core.agent.SafeLog.TOOL_ARGS_PLACEHOLDER
                    + " verified=" + verified);
            return result(capability, "已开始导航到“" + destination + "”", readback, verified, started);
        }

        if ("memory.preference.save".equals(capability)) {
            String key = String.valueOf(call.argument("key"));
            String value = String.valueOf(call.argument("value"));
            memoryStore.putPreference(userId(request), key, value);
            readback.put(key, memoryStore.getPreference(userId(request), key));
            // 第七轮 P2-4:key 可能是 home_address / common_destinations 等,暴露 key 名
            // 等于暴露"用户保存过哪些敏感事实"。整段用占位符包裹。
            Log.d(TAG, "[Provider] memory.save user=" + userId(request)
                    + " key=" + com.matrix.agent.core.agent.SafeLog.TOOL_ARGS_PLACEHOLDER
                    + " value=" + com.matrix.agent.core.agent.SafeLog.TOOL_ARGS_PLACEHOLDER);
            return result(capability, "已记住你的温度偏好：" + value + "℃", readback, true, started);
        }

        if ("memory.preference.get".equals(capability)) {
            String key = String.valueOf(call.argument("key"));
            String value = memoryStore.getPreference(userId(request), key);
            if (value == null) {
                Log.d(TAG, "[Provider] memory.get user=" + userId(request)
                        + " key=" + com.matrix.agent.core.agent.SafeLog.TOOL_ARGS_PLACEHOLDER
                        + " -> not found");
                return new ToolResult(
                        ToolResult.Status.EXECUTION_FAILED,
                        capability,
                        "还没有保存温度偏好",
                        Collections.emptyMap(),
                        false,
                        elapsedMillis(started));
            }
            readback.put(key, value);
            Log.d(TAG, "[Provider] memory.get user=" + userId(request)
                    + " key=" + key + " -> found");
            return result(capability, "你保存的温度偏好是 " + value + "℃", readback, true, started);
        }

        if ("knowledge.answer".equals(capability)) {
            String question = String.valueOf(call.argument("question"));
            readback.put("answerSource", "mock-offline-knowledge");
            return result(
                    capability,
                    "这是离线问答占位结果。已收到问题：“" + question + "”。后续由 Model Gateway 接入真实模型。",
                    readback,
                    true,
                    started);
        }

        Log.w(TAG, "[Provider] unsupported capability=" + capability);
        return new ToolResult(
                ToolResult.Status.EXECUTION_FAILED,
                capability,
                "Mock Provider 不支持该 Capability",
                Collections.emptyMap(),
                false,
                elapsedMillis(started));
    }

    public Map<String, Object> snapshotVehicleState() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(observedState));
    }

    /** Test hook: the next vehicle command is accepted but not reflected by readback. */
    public void failNextVehicleReadback() {
        Log.i(TAG, "[Provider] failNextVehicleReadback ARMED");
        failNextVehicleReadback.set(true);
    }

    private void commandAndApply(String key, Object value) {
        commandedState.put(key, value);
        if (failNextVehicleReadback.getAndSet(false)) {
            Log.w(TAG, "[Provider] readback intentionally skipped for key=" + key);
            return;
        }
        observedState.put(key, commandedState.get(key));
    }

    private static ToolResult result(
            String capability,
            String message,
            Map<String, Object> observed,
            boolean verified,
            long started) {
        return new ToolResult(
                verified ? ToolResult.Status.SUCCESS : ToolResult.Status.VERIFICATION_FAILED,
                capability,
                message,
                observed,
                verified,
                elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static String userId(AgentRequest request) {
        return request.getActor() == Actor.DRIVER ? "demo-driver" : "demo-passenger";
    }
}
