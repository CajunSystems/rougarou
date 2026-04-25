package com.cajunsystems.rougarou.core;

import com.cajunsystems.gumbo.serialization.LogSerializer;
import com.cajunsystems.rougarou.core.events.AgentError;
import com.cajunsystems.rougarou.core.events.AssistantMessage;
import com.cajunsystems.rougarou.core.events.InferenceCompleted;
import com.cajunsystems.rougarou.core.events.InferenceFailed;
import com.cajunsystems.rougarou.core.events.InferenceRequested;
import com.cajunsystems.rougarou.core.events.RougarouEvent;
import com.cajunsystems.rougarou.core.events.SessionClosed;
import com.cajunsystems.rougarou.core.events.SessionCreated;
import com.cajunsystems.rougarou.core.events.ToolCompleted;
import com.cajunsystems.rougarou.core.events.ToolFailed;
import com.cajunsystems.rougarou.core.events.ToolRequested;
import com.cajunsystems.rougarou.core.events.UserMessage;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;

/**
 * Polymorphic serializer for the sealed {@link RougarouEvent} hierarchy.
 *
 * <p>Each variant is registered with a stable id so on-disk events can be deserialized after
 * the codebase is restarted with new variants added (only at the end of the registration list).
 */
public final class RougarouEventSerializer implements LogSerializer<RougarouEvent> {

    private static final int POOL_CAPACITY = 8;
    private static final int OUTPUT_INITIAL_CAPACITY = 512;

    private final Pool<Kryo> pool;

    public RougarouEventSerializer() {
        this.pool = new Pool<>(true, false, POOL_CAPACITY) {
            @Override
            protected Kryo create() {
                Kryo kryo = new Kryo();
                kryo.setRegistrationRequired(false);
                kryo.register(SessionCreated.class, 100);
                kryo.register(SessionClosed.class, 101);
                kryo.register(UserMessage.class, 102);
                kryo.register(AssistantMessage.class, 103);
                kryo.register(InferenceRequested.class, 104);
                kryo.register(InferenceRequested.Turn.class, 105);
                kryo.register(InferenceCompleted.class, 106);
                kryo.register(InferenceCompleted.ToolCall.class, 107);
                kryo.register(InferenceFailed.class, 108);
                kryo.register(ToolRequested.class, 109);
                kryo.register(ToolCompleted.class, 110);
                kryo.register(ToolFailed.class, 111);
                kryo.register(AgentError.class, 112);
                return kryo;
            }
        };
    }

    @Override
    public byte[] serialize(RougarouEvent value) {
        Kryo kryo = pool.obtain();
        try (Output output = new Output(OUTPUT_INITIAL_CAPACITY, -1)) {
            kryo.writeClassAndObject(output, value);
            return output.toBytes();
        } finally {
            pool.free(kryo);
        }
    }

    @Override
    public RougarouEvent deserialize(byte[] data) {
        Kryo kryo = pool.obtain();
        try (Input input = new Input(data)) {
            return (RougarouEvent) kryo.readClassAndObject(input);
        } finally {
            pool.free(kryo);
        }
    }
}
