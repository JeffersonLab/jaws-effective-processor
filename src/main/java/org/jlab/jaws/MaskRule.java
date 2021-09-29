package org.jlab.jaws;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.jlab.jaws.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG;

/**
 * Adds a Masked override to an alarm with an active parent alarm and removes the Masked override when the parent
 * alarm is no longer active.
 */
public class MaskRule extends ProcessingRule {

    private static final Logger log = LoggerFactory.getLogger(MaskRule.class);

    String overridesOutputTopic;

    public static final Serdes.StringSerde MONOLOG_KEY_SERDE = new Serdes.StringSerde();
    public static final SpecificAvroSerde<Alarm> MONOLOG_VALUE_SERDE = new SpecificAvroSerde<>();

    public static final SpecificAvroSerde<OverriddenAlarmKey> OVERRIDE_KEY_SERDE = new SpecificAvroSerde<>();
    public static final SpecificAvroSerde<OverriddenAlarmValue> OVERRIDE_VALUE_SERDE = new SpecificAvroSerde<>();

    public static final Serdes.StringSerde MASK_STORE_KEY_SERDE = new Serdes.StringSerde();
    public static final Serdes.StringSerde MASK_STORE_VALUE_SERDE = new Serdes.StringSerde();

    public MaskRule(String inputTopic, String outputTopic, String overridesOutputTopic) {
        super(inputTopic, outputTopic);
        this.overridesOutputTopic = overridesOutputTopic;
    }

    @Override
    public Properties constructProperties() {
        final Properties props = super.constructProperties();

        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "jaws-auto-override-processor-mask");

        return props;
    }

    @Override
    public Topology constructTopology(Properties props) {
        final StreamsBuilder builder = new StreamsBuilder();

        // If you get an unhelpful NullPointerException in the depths of the AVRO deserializer it's likely because you didn't set registry config
        Map<String, String> config = new HashMap<>();
        config.put(SCHEMA_REGISTRY_URL_CONFIG, props.getProperty(SCHEMA_REGISTRY_URL_CONFIG));

        MONOLOG_VALUE_SERDE.configure(config, false);

        OVERRIDE_KEY_SERDE.configure(config, true);
        OVERRIDE_VALUE_SERDE.configure(config, false);

        final KTable<String, Alarm> monologTable = builder.table(inputTopic,
                Consumed.as("Monolog-Table").with(MONOLOG_KEY_SERDE, MONOLOG_VALUE_SERDE));

        final KStream<String, Alarm> monologStream = monologTable.toStream();

        // TODO: Foreign key join on maskedBy field?  Parent active/normal status part of computation
        // Computing parent effective state might be too much (parent overrides) - just use actual parent active or not?
        KStream<String, Alarm> maskOverrideMonolog = monologStream.filter(new Predicate<String, Alarm>() {
            @Override
            public boolean test(String key, Alarm value) {
                System.err.println("Filtering: " + key + ", value: " + value);
                return value.getOverrides().getMasked() == null && value.getTransitions().getTransitionToActive();
            }
        });

        KStream<OverriddenAlarmKey, OverriddenAlarmValue> maskOverrides = maskOverrideMonolog.map(new KeyValueMapper<String, Alarm, KeyValue<OverriddenAlarmKey, OverriddenAlarmValue>>() {
            @Override
            public KeyValue<OverriddenAlarmKey, OverriddenAlarmValue> apply(String key, Alarm value) {
                return new KeyValue<>(new OverriddenAlarmKey(key, OverriddenAlarmType.Masked), new OverriddenAlarmValue(new MaskedOverride()));
            }
        });

        maskOverrides.to(overridesOutputTopic, Produced.as("Mask-Overrides").with(OVERRIDE_KEY_SERDE, OVERRIDE_VALUE_SERDE));


        KStream<String, Alarm> unmaskOverrideMonolog = monologStream.filter(new Predicate<String, Alarm>() {
            @Override
            public boolean test(String key, Alarm value) {
                System.err.println("Filtering: " + key + ", value: " + value);
                return value.getOverrides().getMasked() != null && value.getTransitions().getTransitionToNormal();
            }
        });

        KStream<OverriddenAlarmKey, OverriddenAlarmValue> unmaskOverrides = maskOverrideMonolog.map(new KeyValueMapper<String, Alarm, KeyValue<OverriddenAlarmKey, OverriddenAlarmValue>>() {
            @Override
            public KeyValue<OverriddenAlarmKey, OverriddenAlarmValue> apply(String key, Alarm value) {
                return new KeyValue<>(new OverriddenAlarmKey(key, OverriddenAlarmType.Masked), null);
            }
        });

        unmaskOverrides.to(overridesOutputTopic, Produced.as("Unmask-Overrides").with(OVERRIDE_KEY_SERDE, OVERRIDE_VALUE_SERDE));


        final StoreBuilder<KeyValueStore<String, String>> storeBuilder = Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore("MaskStateStore"),
                MASK_STORE_KEY_SERDE,
                MASK_STORE_VALUE_SERDE
        ).withCachingEnabled();

        builder.addStateStore(storeBuilder);

        final KStream<String, Alarm> passthrough = monologStream.transform(
                new MaskRule.MsgTransformerFactory(storeBuilder.name()),
                Named.as("MaskTransitionProcessor"),
                storeBuilder.name());

        passthrough.to(outputTopic, Produced.as("Mask-Passthrough")
                .with(MONOLOG_KEY_SERDE, MONOLOG_VALUE_SERDE));

        return builder.build();
    }

    private static final class MsgTransformerFactory implements TransformerSupplier<String, Alarm, KeyValue<String, Alarm>> {

        private final String storeName;

        /**
         * Create a new MsgTransformerFactory.
         *
         * @param storeName The state store name
         */
        public MsgTransformerFactory(String storeName) {
            this.storeName = storeName;
        }

        /**
         * Return a new {@link Transformer} instance.
         *
         * @return a new {@link Transformer} instance
         */
        @Override
        public Transformer<String, Alarm, KeyValue<String, Alarm>> get() {
            return new Transformer<String, Alarm, KeyValue<String, Alarm>>() {
                private KeyValueStore<String, String> store;
                private ProcessorContext context;

                @Override
                @SuppressWarnings("unchecked") // https://cwiki.apache.org/confluence/display/KAFKA/KIP-478+-+Strongly+typed+Processor+API
                public void init(ProcessorContext context) {
                    this.context = context;
                    this.store = (KeyValueStore<String, String>) context.getStateStore(storeName);
                }

                @Override
                public KeyValue<String, Alarm> transform(String key, Alarm value) {
                    System.err.println("Processing key = " + key + ", value = " + value);

                    // TODO: store and compute both masking and unmasking state
                    boolean masking = false;
                    boolean unmasking = false;

                    if(value.getOverrides().getMasked() != null) {

                        // Check if already mask in-progress
                        masking = store.get(key) != null;

                        // Check if we need to mask
                        boolean needToMask = value.getTransitions().getTransitionToActive();

                        if (needToMask) {
                            masking = true;
                        }
                    }

                    store.put(key, masking ? "y" : null);

                    if (masking) { // Update transition state
                        //value.getTransitions().setMasking(true);
                    }

                    return new KeyValue<>(key, value);
                }

                @Override
                public void close() {
                    // Nothing to do
                }
            };
        }
    }
}
