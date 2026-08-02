package com.matrix.agent.core.agent;
import com.matrix.agent.core.identity.*;
import com.matrix.agent.core.session.*;
import com.matrix.agent.core.tool.*;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @deprecated V0.4.0 起被 {@link DemoModelGateway} 取代。保留作为关键词匹配的参考实现,
 * 后续版本会删除。
 */
@Deprecated
public final class DemoPlanner implements Planner {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d{1,3})");

    @Override
    public TaskPlan plan(AgentRequest request, SessionContext sessionContext) {
        String text = request.getText();
        List<ToolCall> steps = new ArrayList<>();

        if (containsAny(text, "ADAS", "adas", "辅助驾驶")) {
            steps.add(call("vehicle.adas.set_enabled", "enabled", true));
            return new TaskPlan("请求控制禁止能力", steps);
        }

        if (text.contains("记住") && containsAny(text, "温度", "度")) {
            Integer temperature = findNumber(text);
            if (temperature != null) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("key", "preferred_temperature");
                args.put("value", String.valueOf(temperature));
                steps.add(new ToolCall("memory.preference.save", args));
            }
        }

        if (containsAny(text, "喜欢多少度", "常用温度", "温度偏好")) {
            steps.add(call("memory.preference.get", "key", "preferred_temperature"));
        }

        boolean sameForPassenger = text.contains("副驾也一样") || text.contains("副驾驶也一样");
        if (sameForPassenger) {
            Integer lastTemperature = sessionContext.getLastTemperature();
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("zone", "passenger");
            if (lastTemperature != null) {
                args.put("temperature", lastTemperature);
            }
            steps.add(new ToolCall("vehicle.climate.set_temperature", args));
        } else if (containsAny(text, "空调", "温度") && !text.contains("偏好") && !text.contains("喜欢多少度")) {
            Integer temperature = findNumber(text);
            if (temperature == null && containsAny(text, "调高一点", "高一点")) {
                Integer previous = sessionContext.getLastTemperature();
                temperature = previous == null ? 24 : previous + 2;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("zone", resolveZone(text, request.getActor()));
            if (temperature != null) {
                args.put("temperature", temperature);
            }
            steps.add(new ToolCall("vehicle.climate.set_temperature", args));
        }

        if (containsAny(text, "座椅加热", "座椅温度")) {
            Integer level = findNumber(text);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("zone", resolveZone(text, request.getActor()));
            args.put("level", level == null ? 2 : level);
            steps.add(new ToolCall("vehicle.seat.set_heating_level", args));
        }

        if (text.contains("电量")) {
            steps.add(new ToolCall("vehicle.info.get_battery", new LinkedHashMap<>()));
        }

        if (text.contains("胎压")) {
            steps.add(new ToolCall("vehicle.info.get_tire_pressure", new LinkedHashMap<>()));
        }

        if (containsAny(text, "导航", "带我去")) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("destination", resolveDestination(text));
            steps.add(new ToolCall("navigation.start_route", args));
        }

        if (steps.isEmpty()) {
            steps.add(call("knowledge.answer", "question", text));
        }

        return new TaskPlan("离线规划得到 " + steps.size() + " 个步骤", steps);
    }

    private static ToolCall call(String capability, String key, Object value) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(key, value);
        return new ToolCall(capability, args);
    }

    private static Integer findNumber(String text) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static String resolveZone(String text, Actor actor) {
        if (containsAny(text, "副驾", "副驾驶")) {
            return "passenger";
        }
        if (containsAny(text, "主驾", "驾驶位")) {
            return "driver";
        }
        return actor == Actor.DRIVER ? "driver" : "passenger";
    }

    private static String resolveDestination(String text) {
        if (containsAny(text, "回家", "回 家")) {
            return "家";
        }
        if (text.contains("公司")) {
            return "公司";
        }
        int index = text.indexOf("导航到");
        if (index >= 0) {
            return cleanDestination(text.substring(index + 3));
        }
        index = text.indexOf("导航去");
        if (index >= 0) {
            return cleanDestination(text.substring(index + 3));
        }
        return "未指定目的地";
    }

    private static String cleanDestination(String value) {
        return value.replace("。", "").replace("，", "").trim();
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
