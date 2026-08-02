package com.matrix.agent.core.identity;

import java.util.Locale;
import java.util.UUID;

public final class AgentRequest {
    private final String requestId;
    private final String sessionId;
    private final String text;
    private final Actor actor;
    private final VehicleZone occupantZone;
    private final ExplicitIntentConstraints explicitIntent;
    private final int displayId;
    private final int audioZoneId;
    private final InputSource inputSource;
    private final String languageTag;
    private final float asrConfidence;
    private final long createdAtMillis;
    private final long deadlineAtMillis;
    private final CancellationToken cancellationToken;

    public AgentRequest(String text, Actor actor) {
        this(builder(text, actor)
                .sessionId("demo-" + actor.name().toLowerCase(Locale.ROOT))
                .occupantZone(actor == Actor.DRIVER ? VehicleZone.DRIVER : VehicleZone.PASSENGER)
                .explicitIntent(ExplicitIntentConstraints.extractFrom(text)));
    }

    private AgentRequest(Builder builder) {
        requestId = UUID.randomUUID().toString();
        text = builder.text == null ? "" : builder.text.trim();
        actor = builder.actor;
        sessionId = builder.sessionId == null || builder.sessionId.trim().isEmpty()
                ? requestId : builder.sessionId.trim();
        occupantZone = builder.occupantZone;
        explicitIntent = builder.explicitIntent == null
                ? ExplicitIntentConstraints.empty() : builder.explicitIntent;
        displayId = builder.displayId;
        audioZoneId = builder.audioZoneId;
        inputSource = builder.inputSource;
        languageTag = builder.languageTag;
        asrConfidence = builder.asrConfidence;
        createdAtMillis = System.currentTimeMillis();
        deadlineAtMillis = createdAtMillis + builder.timeoutMillis;
        cancellationToken = builder.cancellationToken;
    }

    public String getRequestId() { return requestId; }
    public String getSessionId() { return sessionId; }
    public String getText() { return text; }
    public Actor getActor() { return actor; }
    public VehicleZone getOccupantZone() { return occupantZone; }
    public ExplicitIntentConstraints getExplicitIntent() { return explicitIntent; }
    public int getDisplayId() { return displayId; }
    public int getAudioZoneId() { return audioZoneId; }
    public InputSource getInputSource() { return inputSource; }
    public String getLanguageTag() { return languageTag; }
    public float getAsrConfidence() { return asrConfidence; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public long getDeadlineAtMillis() { return deadlineAtMillis; }
    public CancellationToken getCancellationToken() { return cancellationToken; }
    public boolean isCancelled() { return cancellationToken.isCancelled(); }
    public boolean isTimedOut() { return System.currentTimeMillis() >= deadlineAtMillis; }
    public long remainingMillis() { return Math.max(0L, deadlineAtMillis - System.currentTimeMillis()); }

    public static Builder builder(String text, Actor actor) {
        return new Builder(text, actor);
    }

    public static final class Builder {
        private final String text;
        private final Actor actor;
        private String sessionId;
        private VehicleZone occupantZone;
        private ExplicitIntentConstraints explicitIntent;
        private int displayId;
        private int audioZoneId;
        private InputSource inputSource = InputSource.TOUCH;
        private String languageTag = "zh-CN";
        private float asrConfidence = 1.0f;
        private long timeoutMillis = 60_000L;
        private CancellationToken cancellationToken = new CancellationToken();

        private Builder(String text, Actor actor) {
            if (actor == null) throw new IllegalArgumentException("actor 不能为空");
            this.text = text;
            this.actor = actor;
            occupantZone = actor == Actor.DRIVER ? VehicleZone.DRIVER : VehicleZone.PASSENGER;
            explicitIntent = ExplicitIntentConstraints.extractFrom(text);
        }

        public Builder sessionId(String value) { sessionId = value; return this; }
        public Builder occupantZone(VehicleZone value) { occupantZone = value; return this; }
        public Builder explicitIntent(ExplicitIntentConstraints value) { explicitIntent = value; return this; }
        public Builder displayId(int value) { displayId = value; return this; }
        public Builder audioZoneId(int value) { audioZoneId = value; return this; }
        public Builder inputSource(InputSource value) { inputSource = value; return this; }
        public Builder languageTag(String value) { languageTag = value; return this; }
        public Builder asrConfidence(float value) { asrConfidence = value; return this; }
        public Builder timeoutMillis(long value) { timeoutMillis = value; return this; }
        public Builder cancellationToken(CancellationToken value) { cancellationToken = value; return this; }
        public AgentRequest build() {
            if (occupantZone == null) throw new IllegalArgumentException("occupantZone 不能为空");
            if (inputSource == null) throw new IllegalArgumentException("inputSource 不能为空");
            if (displayId < 0) throw new IllegalArgumentException("displayId 不能小于 0");
            if (audioZoneId < 0) throw new IllegalArgumentException("audioZoneId 不能小于 0");
            if (languageTag == null || languageTag.trim().isEmpty()) {
                throw new IllegalArgumentException("languageTag 不能为空");
            }
            if (!Float.isFinite(asrConfidence) || asrConfidence < 0f || asrConfidence > 1f) {
                throw new IllegalArgumentException("asrConfidence 必须在 0 到 1 之间");
            }
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis 必须大于 0");
            if (cancellationToken == null) throw new IllegalArgumentException("cancellationToken 不能为空");
            return new AgentRequest(this);
        }
    }
}
