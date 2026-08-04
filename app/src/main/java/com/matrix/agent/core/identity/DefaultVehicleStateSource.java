package com.matrix.agent.core.identity;

/**
 * V0.4.3 Stage B:生产环境默认 {@link VehicleStateSource} 占位。
 *
 * <p>V0.4.3 不引入 {@code android.car.*} 依赖。本类内部委托给
 * {@link MockVehicleStateSource} 的默认 state({@link VehicleState#satisfyAllPredicates()}),
 * 确保所有 capability 在 mock 环境下放行。
 *
 * <p>后续版本计划:
 * <ul>
 *   <li>接入 {@code android.car.hardware.CarPropertyManager}</li>
 *   <li>实时读取 gear / speed / charging / batteryPercent</li>
 *   <li>读失败时降级为安全 default(本类的 mock state)避免阻塞 capability 调用</li>
 * </ul>
 */
public final class DefaultVehicleStateSource implements VehicleStateSource {
    private final MockVehicleStateSource delegate;

    public DefaultVehicleStateSource() {
        this.delegate = new MockVehicleStateSource();
    }

    @Override
    public VehicleState snapshot() {
        // TODO 后续版本:接 CarPropertyManager,读 gear / speed / charging / batteryPercent。
        // 当前直接返回 mock state,与 V0.4.2 satisfyAllPredicates 默认一致。
        return delegate.snapshot();
    }

    /** 测试 / demo 用:运行时切换状态(如模拟 gear=D 触发 PARKED_ONLY 拒绝)。 */
    public DefaultVehicleStateSource setGear(VehicleState.Gear gear) {
        delegate.setGear(gear);
        return this;
    }

    public DefaultVehicleStateSource setSpeedKmh(double speedKmh) {
        delegate.setSpeedKmh(speedKmh);
        return this;
    }

    public DefaultVehicleStateSource setEngineRunning(boolean running) {
        delegate.setEngineRunning(running);
        return this;
    }

    public DefaultVehicleStateSource setCharging(boolean charging) {
        delegate.setCharging(charging);
        return this;
    }
}
