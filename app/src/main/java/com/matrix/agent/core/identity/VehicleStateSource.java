package com.matrix.agent.core.identity;

/**
 * V0.4.3 Stage B:车辆运动状态源抽象。
 *
 * <p>Repository.execute build AgentRequest 时调 {@link #snapshot()} 注入当前状态,
 * PolicyEngine 用它判定 capability 的 {@code requiredVehicleStates} 前置约束
 * (PARKED_ONLY / STOPPED_OR_PARKED / ENGINE_RUNNING / NOT_CHARGING)。
 *
 * <p>V0.4.3 实现两个:
 * <ul>
 *   <li>{@link MockVehicleStateSource} —— 可配置 gear/speed/charging,供 demo 和测试切换</li>
 *   <li>{@link DefaultVehicleStateSource} —— V0.6.0 接 {@code CarPropertyManager} 的占位</li>
 * </ul>
 */
public interface VehicleStateSource {
    VehicleState snapshot();
}
